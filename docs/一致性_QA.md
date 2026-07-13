# 一致性_QA

## Q1：这个系统怎么保证不超卖？

核心是三层兜底。

第一层是 Redis Lua。入口只有库存大于 0 才能原子扣减成功，绝大多数请求不会进入数据库。

第二层是 MySQL 条件扣库存。消费者事务里扣 MySQL 库存时，SQL 带 `available_stock > 0` 条件。即使 Redis 入口出现偏差，数据库也不会把库存扣成负数。

`sku_stock.version` 已删除。旧字段只做 `version = version + 1`，从未出现在 `WHERE version = ?` 中，不构成乐观锁；真正的原子 compare-and-set 是 `available_stock > 0` 条件更新。

第三层是订单唯一约束。同一活动、同一用户、同一 SKU 只能有一条订单，避免重复消费或重复请求生成多单。

压测证据也能支撑这个说法：库存 1000，正式不同用户和重复请求场景最终都是成功订单 1000、可用库存 0、重复订单组 0。

## Q2：Redis 已经扣库存，MQ 发送失败怎么办？

本项目先写本地消息表，再发 MQ。只要本地消息表写成功，即使 MQ 发送失败，消息也会留在 `seckill_message` 里。发送失败会标记为失败状态，重试任务后续扫描并重投。

publisher confirm/return 回调不会无条件覆盖消息状态。confirm ack 只允许 `SENDING -> SENT`，confirm nack 或 return 只允许 `SENDING -> CONFIRM_FAILED/RETURNED`。如果消息已经进入 `CONSUMING`、`CONSUMED`、`TIMEOUT` 或其他非发布阶段状态，发布回调不会再把它改回失败或成功，避免生产者回调和消费者竞争。

如果本地消息表写入本身失败，代码会立刻补偿 Redis：删除用户幂等 key，删除订单状态，把已扣的库存 key 加回去。开启 Redis 分桶时，只回补实际扣减的 bucket；总库存 key 不再做实时扣减事实，避免 bucket 和 total 两条命令之间出现漂移。

## Q2.1：秒杀 token 怎么保证一次性？

token 校验不是先 `GET` 再 `DELETE`，而是用 Lua compare-and-delete 原子完成：只有 Redis 里保存的 token 和请求 token 完全一致时才删除并放行。这样同一个 token 并发重放时，最多只有一个请求能消费成功。

即使 token 层出现异常，后面还有 Redis 用户 SKU 幂等 key 和 MySQL 唯一索引兜底，所以不会重复成单。

## Q3：MQ confirm ack 之后消费者还没处理，会不会丢？

confirm ack 只代表消息被 RabbitMQ 接收，不代表订单已经落库。因此消息会处于 `SENT`，直到消费者处理成功才更新为 `CONSUMED`。

如果消费者还没处理，消息仍在 RabbitMQ 或未确认状态里；如果后续出现异常，本地消息状态和补偿记录可以帮助恢复。稳定代表轮次中可以看到 `local_messages=1000`、`consumed_messages=1000`、`success_orders=1000`；优化版 unique r3 则是 1002 条消息进入终态，对应 1000 个成功订单和 2 个 FAILED 订单。两种证据都说明消息结果可解释，但不能把 consumed 数永远等同于成功订单数。

## Q4：消费者重复消费怎么办？

消费者先把本地消息从 `SENT/SENDING/REPLAYED` CAS 成 `CONSUMING`。如果消息已经是 `TIMEOUT/DEAD/CONSUMED`，说明它已经被超时关闭、人工处理或消费完成，消费者会直接 ack 跳过，避免状态反转。

真正调用订单事务时，如果订单唯一索引触发 `DuplicateKeyException`，说明订单事实已经存在。代码会记录 `DUPLICATE_CONSUME_ACKED`，并且仍然通过 `CONSUMING -> CONSUMED` 的 CAS 收敛消息；只有 CAS 成功才写 Redis 成功状态，然后 ack。

这个处理是幂等消费的一种常见方式：重复消息不再重复改库存，而是承认已有事实。

## Q4.1：消费者在 CONSUMING 后宕机会不会卡住？

这个风险已经补了专门的恢复任务。`CONSUMING` 现在被当成带租约的处理中状态，复用 `seckill_message.updated_at` 作为处理开始时间。

`ConsumingMessageRecoveryJob` 会扫描超过处理窗口的 stale `CONSUMING` 消息。如果 MySQL 订单已经存在，说明订单事实已经提交但消息没来得及标终态，就把消息 CAS 成 `CONSUMED`，并写 Redis 成功状态。如果订单不存在，说明可能在落单前崩了，就把消息 CAS 成 `FAILED`，`next_retry_at` 设为当前时间，交给已有 retry job 重新投递。

面试口径是：`CONSUMING` 解决的是超时任务和消费者的状态竞争；租约恢复解决的是消费者崩溃后的处理中悬挂。

## Q5：Redis 库存和 MySQL 库存不一致怎么办？

项目有定时对账任务 `StockReconcileJob`。开启 MySQL 分段库存时，`sku_stock_segment` 是扣减事实，`sku_stock` 汇总表由任务同步，因此压测采样撞上同步窗口时汇总值可能暂时滞后。任务按分段库存事实计算未完成消息数，然后得到 Redis 期望库存：

```text
expectedRedisStock = max(0, mysqlAvailableStock - unfinishedMessages)
```

如果 Redis 当前库存为空或不等于期望库存，就重写 Redis 总库存和 bucket 库存，并写一条补偿记录。

为什么要扣掉未完成消息？因为这些请求已经通过 Redis 准入，可能正在 MQ 或消费者链路上。如果恢复时不预留这部分库存，就可能放出过多入口请求。

## Q6：Redis 分桶会不会导致少卖？

旧逐桶路径可能因为多次网络往返放大尾延迟。当前单机 Redis 默认把全部 bucket 一次传给 Lua，脚本从用户 hash 起点环形扫描并原子扣减，不会因为只检查单个 bucket 而少卖。

Redis Cluster 下跨 slot 无法直接执行多 key 单 Lua，因此会回退逐桶兼容路径。项目的取舍是：入口用分桶降低热点，最终靠 MySQL 事实和对账修复 Redis 库存。面试时要说“分桶是性能优化，不是最终事实来源”。

## Q7：为什么 Redis 宕机时选择 fail-closed？

秒杀入口高度依赖 Redis 的库存、token、限流和幂等。如果 Redis 不可用还继续放量，系统就会失去入口准入和库存判断，很容易把流量直接打到 MySQL。

所以健康检查连续失败后，项目会把入口切到恢复态，token 和下单直接返回 503。这个选择牺牲短时间可用性，换取库存事实不被打乱。

面试时不要说“高可用无感恢复”。本项目真实口径是“Redis 故障时 fail-closed，恢复后重建库存并对账”。

## Q8：订单排队太久怎么办？

`OrderTimeoutJob` 会扫描长时间未完成的本地消息，把它们标记为 `TIMEOUT`，并把 Redis 订单状态写成“排队超时”。同时写日志和补偿记录，并删除用户 SKU 幂等 key，让 10 分钟超时后的用户不必等到 30 分钟幂等 TTL 自然过期才能重试。它不会覆盖已经进入 `CONSUMING` 的消息；而且只有 `markTimeout(...)` 实际更新 1 行后才执行这些副作用。

这能避免用户一直看到“排队中”。生产系统里还需要更完整的退款、通知和人工处理流程，本项目只做订单链路演示。

## Q9：消息状态机怎么理解？

可以按这条线讲：

```text
PENDING -> SENDING -> SENT -> CONSUMING -> CONSUMED
                    -> CONFIRM_FAILED / RETURNED / FAILED
失败状态 -> 重试 -> SENDING
重试耗尽 -> DEAD
DEAD / TIMEOUT / FAILED -> REPLAYED -> SENDING
```

`CONSUMING` 是消费者处理中间态，用来防止 confirm 回调、超时任务和消费者互相覆盖。它不是永久状态，而是带超时恢复的 lease 状态。最终态主要是 `CONSUMED`、`TIMEOUT`、`DEAD`。`CONSUMED` 表示订单事实已经收敛，`TIMEOUT` 表示排队太久被关闭，`DEAD` 表示需要人工或管理接口回放。

## Q10：这个系统是一致性强还是最终一致？

入口返回“排队中”时，订单还没有落库，所以用户视角不是强一致。系统采用的是最终一致：Redis 先准入，MQ 异步削峰，MySQL 最终落事实，本地消息表、重试、对账和补偿负责把异常链路收敛。

真正强一致的部分在 MySQL 事务内：订单插入和库存扣减在同一个事务里完成，数据库约束作为最后防线。

## Q11：如果 MySQL 扣库存失败，Redis 已经扣掉的库存怎么办？

这个分支说明 Redis 和 MySQL 之间会短时间不一致。消费者会把消息标记失败、订单状态设为失败，并额外写一条 `FAILED` 订单行；失败结果因此不会只存在于短 TTL Redis 状态或日志中。后续对账任务会按 MySQL 可用库存和未完成消息数修复 Redis。

这也是为什么面试时不能说“每个异常都即时完全补偿”。更准确的说法是：入口失败能即时补偿，消费阶段失败主要靠本地消息状态、补偿记录和对账收敛。

## Q12：压测怎么证明一致性？

2026-07-13 Mac 同机对比的所有主场景轮次都满足：MySQL 成功订单数不超过初始库存，重复订单组为 0，三轮中位数系统错误率为 0。库存 1000 场景最终成功 1000 单，库存 100 场景最终成功 100 单。

个别轮次入口受理数略高于最终成功数：优化版 unique r3 最终形成 1000 个成功订单和 2 个 FAILED 订单，1002 条消息进入消费终态。这个证据证明的是“数据库零超卖、失败可追踪、消息总记录闭合”，不是“入口永远恰好只受理库存数量”。
