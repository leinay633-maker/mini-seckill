# 核心链路_QA

## Q1：这个项目的主链路是什么？

可以这样回答：

主链路是“准入、削峰、落库、补偿”。用户先获取短 TTL 秒杀 token 和隐藏下单 path，下单时做活动校验、path 校验、Redis Lua 限流、token 校验、Redis 幂等和 Redis Lua 扣库存。库存扣成功后先写本地消息表，再投递 RabbitMQ。消费者异步消费消息，在 MySQL 事务里插入订单并扣库存。最后由消息重试、订单超时、Redis 恢复和库存对账把异常链路收敛回来。

对应源码：

- `SeckillServiceImpl.createOrderToken`
- `SeckillServiceImpl.placeOrder`
- `SeckillProducer.send`
- `SeckillConsumer.consume`
- `OrderServiceImpl.createOrderFromConsumingMessage`

## Q2：为什么要先拿 token？

token 是入口削峰和防刷的一层简化实现。用户不能直接无限打下单接口，而是先拿一个短 TTL token。token 写在 Redis 里，下单时通过 Lua compare-and-delete 原子校验并删除，避免同一个 token 并发重放。

这个项目还有 token 资格池。初始化或预热库存时，会把资格池额度写成“库存 * 倍数”，默认倍数是 3，高并发 profile 是 5。这样即使真实库存只有 1000，也不会让无限用户都进入下单链路。

面试边界：这不是完整风控系统。现在有 demo JWT 和数学验证码，但它们是可选演示骨架，没有设备指纹、图片/滑块验证码、黑名单或风险评分。

## Q3：隐藏下单 path 解决什么？

隐藏 path 是防止脚本直接固定打 `/api/seckill/order` 的一层入口保护。用户获取 token 时，服务端会同时生成一个短 TTL `orderPath`，写到 Redis 的 `seckill:path:{activityId}:{userId}:{skuId}`。推荐下单入口是 `/api/seckill/order/{orderPath}`，服务端会先校验 path，再进入真正的下单链路。

这个项目保留了旧 `/api/seckill/order`，主要是为了兼容已有压测脚本和本地演示。面试时要说清楚：隐藏 path 已经和 demo JWT、可选数学验证码形成服务端骨架，但还没有生产级网关校验、图片/滑块验证码、设备指纹和风控评分。

如果面试官要求现场演示，可以按这个顺序讲：先访问 Swagger 看 `Auth`、`Captcha`、`Seckill`、`Order` 分组；需要用户态时先 `/api/auth/login` 拿 JWT；需要验证码时再 `/api/captcha/math` 拿 `captchaId` 和答案；随后 `/api/seckill/token` 返回 `token` 和 `orderPath`；最后调用 `/api/seckill/order/{orderPath}` 并轮询 `/api/order/query`。项目里也准备了 `scripts/smoke-flow.ps1`，可以把这条链路串成一条命令并保存 JSON 证据。

## Q4：限流怎么做？

限流默认使用 Redis ZSET 滑动窗口 Lua：先按当前实例时间删除窗口外成员，再统计窗口内请求数，放行时写入唯一成员并设置与窗口一致的过期时间。项目保留旧固定窗口脚本，通过 `seckill.rate-limit.algorithm=FIXED_WINDOW` 可快速回退。每个请求会按三个维度检查：

- 活动 SKU 维度：控制单个商品总流量。
- 用户维度：控制单用户频率。
- IP 维度：控制单 IP 频率。

默认规则来自 `application.yml`，也可以通过 `seckill_rate_limit_rule` 和 `/api/admin/rate-limit` 动态调整。应用里用 Caffeine 做短 TTL 缓存；“数据库无规则”也缓存为 `Optional.empty()`，避免默认配置下每个请求反复查库。

## Q5：为什么要用 Redis Lua 扣库存？

库存判断和扣减必须是原子的。如果先查 Redis 再扣 Redis，在并发下就可能多个线程都看到还有库存。Lua 脚本把“读取库存、判断是否存在、判断是否大于 0、扣减”放在 Redis 单线程里执行，返回值也明确：

- `-1`：库存 key 不存在。
- `0`：库存不足。
- `1`：扣减成功。

## Q6：库存 bucket 是怎么回事？

普通 Redis 预扣库存会形成一个超级热 key。这个项目把库存拆成多个 bucket key，默认 64 个，高并发 profile 128 个。单机 Redis 默认把全部 bucket key 一次传给 `seckill_stock_sharded.lua`，脚本从用户 hash 对应位置环形扫描并原子扣减，所以一次请求只需要一次 Redis 往返。

Redis Cluster 下多个 bucket 可能分属不同 slot，配置会关闭单 Lua并回退逐桶兼容路径。分桶模式下 bucket sum 是扣减事实，总库存 key 只作为初始化、恢复和对账视图，不在下单时同步 `DECR`，避免 bucket 和 total 两条 Redis 命令之间出现漂移。

## Q7：Redis 幂等 key 解决什么？

`seckill:user:{activityId}:{userId}:sku:{skuId}` 用 `setIfAbsent` 写入，能挡住同一个用户对同一个 SKU 的重复请求。第一次请求通过后，后续重复请求会直接返回重复下单或排队中。

它不是唯一防线。数据库里的订单唯一索引是最终兜底，消费者重复消费时如果遇到唯一键冲突，会认为订单事实已经存在，然后 ack 消息。

## Q8：为什么要先写本地消息表再发 MQ？

这是为了避免“Redis 已扣库存，但 MQ 消息丢了”没有依据可恢复。入口扣 Redis 成功后先插入 `seckill_message`，再发 RabbitMQ。这样只要本地消息表里有记录，后续就可以重试、回放、超时关闭或人工排查。

如果本地消息表插入失败，代码会补偿 Redis：删除用户幂等 key、删除订单状态、把实际扣掉的库存 key 加回去。开启分桶时只回补被扣的 bucket。

## Q9：RabbitMQ confirm/return 怎么用？

生产者发送前把消息标记为 `SENDING`。发送时带 `CorrelationData`，里面是 `requestId`。

- confirm ack：只允许 `SENDING -> SENT`。
- confirm nack：只允许 `SENDING -> CONFIRM_FAILED`。
- return：只允许 `SENDING -> RETURNED`。

失败状态会被 `SeckillMessageRetryJob` 扫描重投。超过最大重试次数后标记为 `DEAD`，再通过管理接口回放。

## Q10：消费者为什么要手动 ack？

消费者只有在业务处理完成后才 ack。正常路径是先把本地消息从 `SENT/SENDING/REPLAYED` CAS 成 `CONSUMING`，再在 MySQL 事务里创建订单、扣库存、写日志，并只从 `CONSUMING` 更新为 `CONSUMED`，然后 ack。

如果消息已经是 `TIMEOUT/DEAD/CONSUMED`，消费者会直接 ack 跳过，避免排队超时后又成功落单。

如果重复消费导致唯一键冲突，说明订单已经存在，消费者仍然通过 `CONSUMING -> CONSUMED` 的 CAS 把消息收敛到已消费，然后 ack。这样避免同一条消息因为重复投递一直失败，也避免绕过状态机直接写终态。

如果是 `TransientDataAccessException` 一类瞬时数据访问异常，消费者会把消息从 `CONSUMING` 回退为 `FAILED`，由已有 retry job 按 `retry_count` 和退避时间接管；确定性 MySQL 库存不足会记录 FAILED 订单并 ack；其他业务或未知异常才进入 DEAD/死信分支。

如果服务在 `CONSUMING` 后崩溃，`ConsumingMessageRecoveryJob` 会按 `updated_at` 判断 stale 消息：订单已存在就补标 `CONSUMED`，订单不存在就回退为 `FAILED` 交给 retry job 重投。

## Q11：MySQL 为什么还要扣库存？

Redis 是入口准入，不是最终事实。最终事实必须落在 MySQL。消费者事务里插订单后，会执行带条件的库存扣减：

```sql
WHERE activity_id = ?
  AND sku_id = ?
  AND segment_id = ?
  AND available_stock > 0
```

如果扣减行数不是 1，说明数据库兜底拒绝了这笔订单，消费者会标记失败并写补偿记录。

## Q12：MySQL 分段库存解决什么？

如果所有订单都更新 `sku_stock` 的同一行，MySQL 行锁会变成热点。项目用 `sku_stock_segment` 把一个 SKU 的库存拆成多个 segment，消费者根据用户 hash 优先扣某个 segment，失败后再找其他可用 segment。

面试时可以说：这不是分库分表，但它能在单库内把单行库存热点拆散。

## Q13：订单查询怎么处理异步状态？

先查 `seckill_order`。如果订单已经落库，就按订单状态返回成功或失败；没有订单时，再查 Redis 精确订单状态 code；最后如果用户 SKU 幂等 key 仍存在，就返回“排队中”，否则返回“未下单”。

这个设计适合异步下单：接口先返回排队，用户再轮询订单状态。查询不再依赖日志字符串包含 `FAILED` 之类的脆弱启发式判断。

## Q14：本地售罄标记有什么作用？

当 Redis Lua 返回库存不足时，应用会把该 SKU 在本机 Caffeine 里短时间标记为售罄。后续请求先在本机被挡掉，减少 Redis 空打。

它只是入口优化，不是库存事实。初始化、预热、Redis 恢复和对账修复库存时会清理售罄标记。

## Q15：这个项目有哪些可观测指标？

项目暴露 Actuator 和 Prometheus 端点，包括：

- `/actuator/health`
- `/actuator/metrics`
- `/actuator/prometheus`

自定义指标包括入口准入、Redis 库存、MQ、订单和回放等维度。项目已经补了 Grafana dashboard、Prometheus scrape 配置和告警规则文件；面试时要说清楚这是可运行监控骨架，只有实际启动容器并保存 targets/health 证据后，才能说本机监控实跑通过。

## Q16：订单 ID 怎么保证多实例唯一？

订单 ID 使用 41 位时间戳、10 位 workerId、12 位毫秒内序列的雪花算法，epoch 从 2024-01-01 开始。workerId 通过 `MINI_SECKILL_WORKER_ID` 配置，多实例 Compose 示例分配 0 到 3。小于 5 ms 的时钟回拨会等待追平，较大回拨拒绝发号并记录指标，避免旧“毫秒乘 1000 + 本地序列”在多实例同毫秒撞号。

## Q17：为什么只把高频日志异步化？

`AsyncSeckillLogWriter` 把高频入口观察日志交给独立 Bean 上的 `@Async` 方法，避免同类自调用绕过 Spring 代理。线程池是有界队列，暴露接收、完成和拒绝计数；`seckill.async-log.enabled=false` 可回退同步写。

本地消息和订单事实没有被异步日志替代：它们仍在关键事务或准入路径同步写入，因为可靠性事实不能为了延迟而丢失。
