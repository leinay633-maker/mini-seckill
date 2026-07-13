# 故障恢复_QA

## Q1：Redis 挂了系统怎么处理？

`RedisHealthCheckJob` 会定时 ping Redis。连续失败达到阈值后，`RedisRecoveryStateService` 会把应用标记为 recovering。

入口侧 `SeckillServiceImpl` 在获取 token 和下单前都会检查恢复态。如果处于恢复态，就直接抛 503，提示 Redis 库存恢复中，秒杀入口暂时关闭。

面试口径：这是 fail-closed。系统宁可短时间不可用，也不在 Redis 库存、幂等和限流失效时继续放量。

## Q2：Redis 恢复接口做了什么？

`RedisRecoveryServiceImpl.recoverRedisStock` 会扫描 MySQL 库存事实表，对每个活动 SKU 加分布式锁，然后重建 Redis：

- Redis 总库存 key。
- Redis bucket 分片库存 key。
- token 资格池 key。
- 本地售罄标记。
- 补偿记录。

关键公式：

```text
expectedRedisStock = max(0, mysqlAvailableStock - unfinishedMessages)
```

`unfinishedMessages` 包括还没最终消费的 `PENDING`、`SENDING`、`SENT`、`CONSUMING`、`FAILED`、`CONFIRM_FAILED`、`RETURNED` 等消息。这样可以给已经进入链路但还没落库的请求预留库存。

## Q3：Redis 故障实测结果是什么？

旧 Windows 历史压测曾单独做过 Redis 故障注入；当前 Mac `REPORT.md` 聚焦优化前后同机三场景，不再把这组跨机历史数据混入性能对比。历史过程是：

- k6 开始后停 Redis。
- Redis 停止约 21 秒后启动。
- 第一次恢复返回 503，后续重试恢复成功。
- 最后还通过重启单应用进程清理未确认消费者状态。

结果：

- 1201 请求。
- 193 成功入队。
- 1008 系统错误。
- 恢复后订单 193。
- 恢复后消息 193。
- 恢复后库存 807。
- RabbitMQ 最终 order/dead queue 都是 0。

结论：恢复后数据一致，但不是无感恢复。

## Q4：RabbitMQ 发送失败怎么办？

入口在 Redis 扣库存成功后先写本地消息表。发送 MQ 前，生产者把消息标记为 `SENDING`。如果发送抛异常，消息会留在本地表里并标记为失败状态，后续 `SeckillMessageRetryJob` 会扫描重投。

confirm ack 只允许 `SENDING -> SENT`。confirm nack 会在消息仍处于 `SENDING` 时标记 `CONFIRM_FAILED`，return 会在消息仍处于 `SENDING` 时标记 `RETURNED`。这些状态会进入重试任务；如果消息已经进入消费或终态，发布回调不会再覆盖它。

如果重试耗尽，就标记为 `DEAD` 并写补偿记录，后续通过管理接口人工回放。

## Q5：RabbitMQ 消费失败怎么办？

消费者按异常性质处理：

- 正常：先把消息从 `SENT/SENDING/REPLAYED` CAS 成 `CONSUMING`，再创建订单、扣库存、标记 `CONSUMED`，然后 ack。
- 已结束：如果消息已经是 `TIMEOUT/DEAD/CONSUMED`，说明已经被超时关闭、人工处理或消费完成，消费者直接 ack 跳过。
- 重复订单：捕获唯一键冲突，认为订单事实已经存在，但仍然通过 `CONSUMING -> CONSUMED` 的 CAS 收敛，再 ack。
- 瞬时数据访问异常：从 `CONSUMING` 回退 `FAILED`，由 retry job 按退避和 `retry_count` 接管。
- 确定性库存不足：事务回滚后写 FAILED 订单，消息进入可解释终态并 ack。
- 其他业务或未知异常：标记 `DEAD`，写补偿记录，设置订单状态失败，并 nack 到死信队列。

本项目当前主要依赖本地消息表做回放依据，不直接把死信队列当作唯一恢复来源。这对个人项目更容易讲清楚，也更容易用 SQL 验证。

## Q5.1：如果服务在 CONSUMING 后崩溃怎么办？

`CONSUMING` 现在按 lease 处理。消费者 CAS 成 `CONSUMING` 后会更新 `updated_at`，恢复任务按 `seckill.consuming-recovery.stale-timeout` 找出 stale `CONSUMING`。

恢复规则是：

1. MySQL 订单已存在：说明业务事实已经提交，只是没来得及把消息标成终态，恢复任务 CAS 成 `CONSUMED`，并把 Redis 订单状态写成成功。
2. MySQL 订单不存在：说明可能在事务前或事务中崩了，恢复任务 CAS 成 `FAILED`，`next_retry_at = now`，让已有 retry job 重投。

这样不会出现“Redis 库存扣了、本地消息永久 CONSUMING、MQ 重投又被直接 ack 掉”的悬挂状态。

## Q6：本地消息重试怎么控制？

`SeckillMessageRetryJob` 定时扫描可重试消息：

- `PENDING`
- `SENDING`
- `FAILED`
- `CONFIRM_FAILED`
- `RETURNED`

配置来自 `seckill.message-retry`：

- 默认固定延迟 15 秒。
- 最大重试 5 次。
- batch size 50。
- 初始退避 5 秒，最大退避 2 分钟。

重试失败会写 `next_retry_at`，重试耗尽会标记 `DEAD` 并写补偿记录。

## Q7：DEAD 消息怎么回放？

`MessageAdminServiceImpl` 支持两个入口：

- 按 requestId 回放单条消息。
- 批量回放 DEAD 消息，limit 最大限制为 200。

回放时先把本地消息状态从 `DEAD`、`TIMEOUT`、`FAILED`、`RETURNED`、`CONFIRM_FAILED` 更新为 `REPLAYED`，再重新发送到 RabbitMQ，并写补偿记录。

面试时要说明：真实生产系统还需要审核、权限、操作人、回放批次和告警，本项目主要展示技术闭环。

## Q8：排队超时怎么处理？

`OrderTimeoutJob` 定时扫描排队太久的消息。默认排队超时时间是 10 分钟。它会把消息标记为 `TIMEOUT`，把订单状态写入 Redis，写日志和补偿记录，并删除用户 SKU 幂等 key，让用户不必等到 30 分钟 TTL 自然过期才能重试。

本轮补了消费前 CAS 和超时任务更新结果判断：已经进入 `CONSUMING` 的消息不会被超时任务覆盖；如果 `markTimeout(...)` 更新 0 行，说明消息状态已经变化，任务不会再写 Redis `TIMEOUT`、日志或补偿。已经 `TIMEOUT` 的消息即使后来从 MQ 到达，消费者也会 ack 跳过，不再落单。这解决的是“用户看到超时后又成功下单”的状态反转风险。

这个机制解决的是用户体验和状态收敛：用户不能永远看到“排队中”。

## Q9：库存对账怎么处理？

`StockReconcileJob` 定时分页扫描 `sku_stock`，如果开启 MySQL 分段库存，就先按 segment 汇总。然后做两件事：

1. 比较 MySQL soldCount 和成功订单数，不一致时写补偿记录，等待人工检查。
2. 按 `MySQL 可用库存 - 未完成消息数` 计算 Redis 期望库存，不一致就修复 Redis 总库存和 bucket 库存。

对账任务也会维护本地售罄标记：有库存就清理售罄标记，没库存就标记售罄。

## Q10：MySQL 连接池或本地消息写入压力怎么处理？

旧 Windows 历史压测日志出现过 `CannotGetJdbcConnectionException`，说明当时不同用户洪峰下，入口链路里的本地消息写入和 MySQL 连接池曾成为压力点。当前 Mac 报告没有复现该异常，只保留这个历史排查结论。

当前项目已有的缓解：

- Redis 扣库后才写本地消息，只有抢到库存的请求才走这一步。
- token 热路径 Redis-first，动态规则“无规则”也做负缓存，减少无效 MySQL 查询。
- 高频观察日志由有界线程池异步写，压测同 jar 开关对照中成功入队 p95 下降 62.1%。
- 高并发 profile 提高 Hikari pool 到 40。
- MQ 消费者并发、最大并发和 prefetch 可配置，避免消费者无限打 MySQL。

后续优化：

- 压测不同连接池大小和 MQ 消费并发。
- 本地消息写入做更细的指标和慢 SQL 观测。
- 分库分表或按活动分库。
- 高频观察日志已经异步化；后续应继续拆分补偿写入与主链路压力，但本地消息和订单事实必须保持同步可靠语义。

## Q11：故障恢复有哪些不能夸大的点？

不能说：

- Redis 无感恢复。
- 任意故障都自动恢复。
- 消息绝不丢。
- RabbitMQ 死信已经完整运营化。
- 管理接口已经有完整登录、角色权限和操作审计。

可以说：

- Redis 故障时入口 fail-closed，避免库存事实不清时继续放量。
- 本地消息表让 MQ 发送失败、confirm 失败和 return 有重试依据。
- DEAD 消息可以通过管理接口回放。
- `/api/admin/**`、`/api/seckill/init`、`/api/seckill/warmup` 和 `/api/recovery/**` 支持可选共享 token 保护，但这不是生产级权限体系。
- 对账任务能按 MySQL 事实修复 Redis 库存。
- 报告里的 Redis 故障场景恢复后订单、消息、库存对账一致。
