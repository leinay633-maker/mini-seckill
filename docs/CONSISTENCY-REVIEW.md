# 下单一致性评审与取舍（2026-10）

评审基准：`68858ab569ae5f133f02fb585ceb35b9fd336a4e`。本轮只处理 MiniSeckill 的工程问题，不扩展商城业务，不把 CI 回归当成真实流量实验。

## 1. 结论：缺的不是更多组件，而是异常时间顺序的证据

基准版已有 Redis/Lua 准入、MySQL 条件库存和唯一索引、本地消息表、RabbitMQ confirm/return、重试、恢复、分段库存、监控，以及有原始文件支撑的同机对照实验。继续加支付、搜索、分库分表不能解决当前最薄弱的一环：**三个系统各自成功时，什么时候才允许对外认定下单成功；异常到来时，谁仍有权修改状态。**

本轮把可验证目标收敛为：MySQL 是订单事实源；成功订单、库存扣减和消息完成同事务；Redis 只是提交后的尽力投影；MQ settlement 不得反向改写已提交事实；重复和恢复必须区分 SUCCESS 与 FAILED；发送端旧快照不能越过允许的状态集合。

这是一轮边界明确的一致性加固，不是“已经解决所有分布式故障”或“达到生产容量”的认证。

## 2. 发现、反例与修复映射

以下反例来自基准代码的控制流推导，不冒充曾在线上发生的事故。对应新测试的实跑记录见 [CI 验证快照](CI-VALIDATION-20261009.md)。

| 优先级 | 基准问题与最短反例 | 本轮处理 | 回归证据入口 |
|---|---|---|---|
| P0 | `OrderServiceImpl.createOrder` 在事务体内写 Redis SUCCESS：写 Redis 后 MySQL 回滚，会留下未提交的成功；Redis 失败也会干扰本来可提交的事务 | `afterCommit` 发布状态，隔离缓存和指标异常；事务回滚不发布成功 | `OrderCommitBoundaryTest`；`OrderCommitBoundaryIT.rollbackAfterServiceBodyLeavesNeitherOrderNorSuccessProjection`、`successIsVisibleToIndependentConnectionBeforeRedisProjection` |
| P0 | 消费成功后的 `basicAck` 与业务调用放在同一 try；ACK 异常走通用业务失败处理 | ACK/NACK 移到业务异常处理之外；一次 delivery 只选择一个 settlement 调用，失败交给连接/容器处理 | `SeckillConsumerTest.ackIoFailureAfterCommitDoesNotTriggerBusinessFailureOrSecondSettlement` 及 runtime 异常用例 |
| P0 | `DuplicateKeyException` 一律按成功：可能撞的是 `order_id`；业务唯一键已有的订单也可能是 FAILED | 按 `(activity_id,user_id,sku_id)` 查询实际订单；无记录/未知状态报错；SUCCESS/FAILED 分流 | 单测 + MySQL 的业务失败重复、订单号碰撞、并发重复投递用例 |
| P0 | 库存拒绝后失败订单 best-effort 写入，消息 FAILED 又可重试；写库失败被吞，或重试后错误地变成功 | 新事务原子写 FAILED 订单与 DEAD 消息；失败持久化错误传播，不由此直接 ACK | `stockRejectionRollsBackThenPersistsFailureAndTerminalMessageAtomically`、`failedCompletionCasMissRollsBackFailureOrder`；consumer 无 ACK 单测 |
| P0 | 发送失败/重试失败 SQL 无状态约束；扫描时未完成、执行时已完成的旧任务可复活终态 | 发送端允许状态集合；消费侧独立 `markDeadFromConsuming`；更新行数为 0 不做补偿/状态投影 | `sendSideWritersCannotOverwriteProtectedStates`、`delayedRetrySnapshotCannotResurrectCommittedMessage`、retry job 单测 |
| P0 | stale CONSUMING 恢复只检查“有订单”，会把 FAILED 或未知状态视作成功 | 按真实 SUCCESS/FAILED 分流；未知状态留待检查；单行/缓存异常不饿死整个扫描批次 | `ConsumingMessageRecoveryJobTest` |
| P1 | 只有 mock 方法被调用，不能证明事务真的提交/回滚；无 Docker 的 skipped 也可能显绿 | 新增生产 DDL + MyBatis + Spring 事务代理 + MySQL 容器测试；保留全量 IT 非跳过断言；归档原始报告和失败日志 | `OrderCommitBoundaryIT`、`run-consistency-evidence.py`、CI artifacts |

## 3. 具体设计决策

### 3.1 MySQL 提交是业务成功边界，Redis 不加入分布式事务

成功路径：插订单 → 条件扣库存 → 成功日志 → `CONSUMING → CONSUMED`，这些 SQL 同一个 MySQL 事务。状态 CAS 未命中必须抛错，已经插入的订单和扣库存一起回滚，不能只打印一条告警后继续成功。

事务提交后写 Redis 状态与新增成功计数。Spring 的 afterCommit 回调虽然发生在提交之后，回调异常仍可能向调用者传播，所以回调内显式隔离 Redis 与指标异常；回调不执行后续 SQL。消费者看到 service 正常返回后才尝试 ACK。生产调用必须经过 Spring 事务代理；直接构造对象的 mock 单测不构成提交证据。

**取舍**：进程在 commit 后、回调前退出，会漏一次缓存更新；本轮没有为缓存单独增加 durable outbox。已有订单查询优先读 MySQL，仍能返回订单事实，但不能由此宣称所有缓存都在固定时限内修复。afterCommit 仍是同步回调：Redis 连接超时可能延迟 ACK，并非异步性能优化。

### 3.2 成功、拒绝、传输确认是不同事实

- SUCCESS/FAILED 是订单业务结果；CONSUMED/DEAD 是本地消息状态；ACK/NACK 是 RabbitMQ settlement，不能互相当证据。
- `DuplicateKeyException` 是“某个唯一索引冲突”，不是“当前用户成功”。只在查到对应业务订单且状态受支持时收敛消息，不重复扣库存、不重复增加新订单成功计数。
- 业务库存拒绝复用本地 `DEAD(7)` 表示不再自动重试，并以 `last_error=terminal business failure: persisted FAILED order` 区分。这条路径会 ACK，**不要求 RabbitMQ DLQ 有一条对应消息**。
- 技术 `FAILED(3)` 仍用于可重试异常。技术 DEAD 的补偿记录仍用于人工排查/回放，不能与业务拒绝混算。

**取舍**：没有新增 REJECTED 状态或数据库迁移，避免扩散到历史报表/运维接口。代价是运营诊断必须结合订单事实与 last_error 分类；未来业务增加可重试购买尝试时，应引入独立 attempt/拒绝原因模型，而不是随意把现有 FAILED 订单改成功。当前业务唯一键会保留 FAILED 订单，因此同一活动/用户/SKU 不支持通过重复请求重新购买。

### 3.3 状态条件防止旧任务越权，不等于完整租约 fencing

发送端失败和重试耗尽仅允许修改 PENDING、SENDING、FAILED、CONFIRM_FAILED、RETURNED。消费失败仅允许从 CONSUMING 更新。更新行数不是附带信息，而是发出补偿和业务失败投影的前提。

自动流程不能把 CONSUMED/TIMEOUT/DEAD 重新写回普通 FAILED；**显式管理回放仍可走既有 REPLAYED 路径**。文档中的“终态保护”专指自动发送/重试写入，不声称终态对所有管理员操作不可改变。

**未解决的 ABA**：旧 worker 看到 CONSUMING，恢复任务把它改 FAILED，后续新 worker 又进入 CONSUMING，此时仅比较状态仍不能识别旧持有者；发送 attempt 的迟到回调也有同类问题。下一步应设计单调 generation/claim token，并在所有完成/失败/恢复 SQL 中携带与核对，而不是缩短或延长 timeout 就称为解决。本轮不把这项设计记成已实现。

### 3.4 指标是诊断线索，不替代数据库事实

`order.success` 只在新订单提交后计数；duplicate reconciliation 不重复增加。`order.status_cache_write_failed` 记录已提交后的缓存异常。

MQ 路径指标仍在 settlement 前记录，且保留了 `duplicate_acked`、`transient_requeued` 等历史标签。因此它们代表本地执行路径，**不是 broker 已确认收到 ACK 的审计记录**；瞬时失败走本地表重试，并非代码直接 basicNack(requeue=true)。进程退出也可能丢最后一次内存指标，应以订单、库存、消息关联和队列事实验收。

## 4. 验收不变量及适用范围

1. 同一业务键最多一条订单；只有成功业务订单消耗一份 MySQL 库存。无分段时 `available + sold = total` 且 `sold = SUCCESS 订单数`；分段开启时先核对分段事实，再等待汇总视图对账。
2. 订单创建事务回滚后，订单、库存和消息完成一起回滚，不发布新的 SUCCESS 缓存投影。
3. CONSUMED 消息按业务键能关联到 SUCCESS 订单；持久化业务 FAILED 的重复投递不能投影成 SUCCESS。
4. 自动重试的迟到写入不修改受保护终态或当前消费态；CAS 失败不生成“已死信”的伪补偿。
5. ACK 失败不把已经提交的订单反向标失败，不再调用第二次 ACK/NACK。

**计数陷阱**：同一业务订单可以由多条不同 request_id 的消息触达。此时可能 12 条 CONSUMED 对应 1 个 SUCCESS 订单，这是本轮固定规模回归的一个输入，不是吞吐实测。一般断言应检查业务键关联、唯一性和库存守恒，不能统一要求消息行数等于订单行数。历史报告中的相等关系只对应其当时的输入工作负载，报告原值不改。

## 5. 保留而未扩展的边界

| 后续优先级 | 尚未解决的问题 | 下一项真正有价值的工作 |
|---|---|---|
| P1 | Redis 预扣与 `insertPending` 之间有进程退出/提交结果不明窗口；目前不是跨系统原子 outbox | 先建立 request 级 reservation intent 与事实对账协议，再验证预扣后退出、DB commit 结果不明、补偿重入；不能直接对所有异常“加回库存” |
| P1 | CONSUMING / SENDING 缺代际 fencing | generation/claim token + 两个 worker、恢复任务交错的真实 MySQL 测试 |
| P1 | 多实例 Redis 恢复态仍有本地状态与恢复屏障的边界 | 在独立环境做逐实例恢复和并发新请求的交错演练；不能把单次串行恢复外推成任意重建安全 |
| P1 | 非默认 `mqFallbackSync=true` 入口仍有事务外冗余状态更新与失败投影；本轮只验证 service 的终态 CAS，未认证整条兼容入口 | 保持默认 false；启用前统一异常分类并给入口本身增加提交结果不明/重复/事务外失败回归 |
| P2 | admission latency 不等于最终订单完成延迟；缓存回调可能延迟 ACK | 同机同参测入队、最终落库、队列积压和失败率；结果待本机实测 |

不引入分库分表、分布式事务框架或新的中间件：当前规模没有本轮实测证据说明它们是瓶颈，且不能修复上述错误的事实判定。没有删除已有功能、降低覆盖率阈值或改写历史性能数字。

## 6. 真实性与来源

- **代码已实现**：本轮业务修复、单元/集成测试、证据脚本；状态与测试入口以上述源码为准。
- **测试通过**：2026-10-09 GitHub Actions Linux/JDK 17 的实跑记录，详见 [CI 验证快照](CI-VALIDATION-20261009.md)。当前编辑环境只运行 Python 校验器测试，没有本地 Maven、Docker 或压测结果。
- **真实流量实验**：`REPORT.md` 与 `REPORT-RELIABILITY.md` 保留用户 2026-07 Mac M1 的实测；它们不是本轮提交的复测结果。
- **新版本性能与 Docker 故障演练**：待本机实测，步骤见 [实验方案](CONSISTENCY-EXPERIMENTS.md)。没有新增估算性能数。

外部语义依据（不作为本项目实验结果）：[Spring TransactionSynchronization.afterCommit](https://docs.spring.io/spring-framework/docs/6.1.21/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit())；[RabbitMQ Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms)。
