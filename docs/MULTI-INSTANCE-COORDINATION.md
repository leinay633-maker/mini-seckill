# 多实例库存协调：写权限、在途预扣与数据库事实

基准：`e2a6f96fc6b55ac9dc97fd03dc3800e6d758960f`。分支：`pro/multi-instance-coordination`。
本篇描述实现与可复现反例。2026-10-10 在云研发容器用三个原生 JVM 复测九个场景，候选 `1fc9e77` 9/9 通过（同机进程信号，不含网络分区与故障转移）。执行入口、场景与验收见 [复测手册](MULTI-INSTANCE-RETEST.md)，实际验证见 [验证记录](MULTI-INSTANCE-VALIDATION.md)。历史 REPORT 与既有 evidence 保留原样。

## 1. 问题不是“多加一把锁”

PR #3 的原文在 `benchmark/evidence/devcloud-linux-x64/pool-budget-33b17bed/reconcile-during-load.txt`。例如 04 组出现 `redisStock=73059 → expectedRedisStock=73195`，同时出现 soldCount / successOrderCount 不一致告警。那次库存是需求的两倍，MySQL 有条件扣减兜底，最终对账均通过，**没有观察到超卖或少卖**。这些日志证明负载期间发生过写回，不证明已经发生业务数据损坏。

原协议有两个独立反例。第一，A 得到 20 秒租约后暂停，B 接管完成修复，A 恢复后仍执行 SET。第二，即使 A 的租约从未过期，它在读 MySQL 与 SET Redis 之间也可能穿插实时扣减；预扣成功而本地消息尚未提交的请求，在 MySQL 中还不存在。几次独立查询还可能分别看到同一订单事务前后的状态，从而产生假不一致。

本次不采用“延长租约”“加看门狗就安全”“先 GET 版本再 SET”或“只有后台任务争锁”的方案。写权限必须在被修改的资源上验证；实时入口也必须参与协议。

## 2. 不变量与适用范围

数据库最终事实仍是 MySQL 成功订单、条件库存更新与本地消息完成的同一事务，沿用 PR #3/#4 的消费事务边界。RabbitMQ 允许重复投递，不承诺端到端 exactly-once。

对一个 `(activityId, skuId)`，同一个 InnoDB 一致性读语句取得：

```text
A = 可用 MySQL 库存（分段开启时取各 segment 之和）
S = 已售 MySQL 库存
O = SUCCESS 订单数
U = 尚无对应业务订单的非终态本地消息数
E = max(0, A - U)
```

先验证总量守恒、非负、`S == O`，再允许把 E 作为 Redis 预算。U 覆盖状态 0/1/3/4/5/8/9/10；CANCELLED=11 不代表已受理业务订单，不计入 U。已有同业务键的成功或失败订单由消费者按真实结果处理，不能把每条重复消息都当作另一件商品。

证明所需前提：所有节点运行本协议、配置一致；读写同一个 MySQL 主库；库存、订单和消息表使用 InnoDB；协调调用不加入外层事务；Redis 是同一单主实例，协调元数据不被外部删除/回滚/驱逐；无终态消息重新打开、无在线库存覆盖初始化。单 Redis Cluster 跨槽、Redis/MySQL 主从切换及数据回滚不在认证范围。

`InventoryCoordinator.requireAutocommitBoundary()` 在预扣、接纳、异常清算、初始化、对账入口拒绝外层事务。否则调用者可能沿用旧 repeatable-read 快照，或在 INSERT 尚未提交时提前清空在途登记。

## 3. 入口：把“没有落库”变成可追踪状态

`coord_reserve.lua` 同时完成：校验用户 requestId 所有权、校验布局和数值、总库存与一个 bucket 扣减、写入在途 hash、以 Redis TIME 记录回收期限、更新版本 nonce。hash 和版本不设 TTL；到期只意味着可以尝试恢复，**不意味着可以直接退款**。所有已知类型/范围检查在写操作之前，避免把 Lua 原子执行误当成报错后的事务回滚。全部 bucket 用一次 `MGET` 读出后逐个校验，只对返回空值的 key 补查 TYPE，以区分缺失与类型错误，结果与逐个 TYPE+GET 相同；任何一个 bucket 缺失或损坏，整次预扣都会拒绝，即使选中的 bucket 正常。bucket 数是部署参数：协议要求单个 Redis 主节点、每次预扣都扣总量 key，分桶不再起分散热点的作用；不分桶（`seckill.stock-shard.enabled=false`，n=0）由同一组脚本支持。2026-10-12 同机对照：关闭分桶后 Redis CPU 降 25%–38%，三 JVM 九场景 9/9，但入口稳定档没有提高（这时瓶颈在 RabbitMQ 发布端），所以默认仍为 100k profile 的 128 个。

版本格式为 `bucketCount:UUID`。每次预扣、登记结算及实际修复都换一个值，用来识别变化和避免 empty→非空→empty 的 ABA；不是单调数字 fencing token。UUID 唯一性是协议假设，布局前缀能拒绝不同 Redis bucket 数量，MySQL segment 配置仍须由部署保持一致。

自动提交的本地消息 INSERT 明确成功后，`accepted` 只清除在途登记，**不退款**。消费者可能已先行提交；消息对应预算由数据库事实接手。清理 Redis 失败保留登记，其他实例之后可以接续。

这一步由 `coord_accept.lua` 一次完成：只传版本、在途、期限、用户锁、状态 5 个 key，在脚本内读登记，校验 owner 前缀、版本布局和 bucket 序号范围后删除登记、更换版本，再投影“排队中”（只有仍持有用户锁的 requestId 才写，不覆盖成功/失败等终态）。接纳不读写库存 key，所以不传 128 个 bucket；清算被拒绝（版本缺失、布局不符、owner 不符）时登记原样保留、交给恢复，投影照常进行，与原来“先清算、再单独投影”的两次调用结果相同。此前这里是 Java 先 HGET 读登记、再带全部 bucket key 调 `coord_settle.lua`、再调 `coord_project.lua`，每单入口的 Redis 往返由 5 次（SET NX、预扣、HGET、清算、投影）降为 3 次（SET NX、预扣、接纳），与 PR #5 之前相同。

### INSERT 结果不明、暂停或强杀

`resolveUncertain` 先尝试插入同 requestId 的 CANCELLED 墓碑：

```sql
INSERT ... status = 11
ON DUPLICATE KEY UPDATE request_id = VALUES(request_id);
```

唯一键与迟到/尚未提交的真实 INSERT 争同一行。真实 INSERT 已提交时，恢复者读回实际消息，不退款；真实事务回滚、墓碑先建立时才退款。旧 JVM 恢复后不能越过墓碑重新受理。数据库暂时不可用或锁等待超时只留下未清算登记，不以“SELECT 看不到”“已经过了 5 秒”推断回滚。

随后 `coord_settle.lua` 用 requestId 和登记内容 CAS（这条路径可能退款，需要 bucket key，仍是 HGET + 清算脚本两步）；退款与删除登记、变更版本在同一脚本内，多个恢复者只能退款一次。迟到状态投影和删除用户锁也必须匹配原 requestId，不能删除后来同用户的新请求。总库存/bucket 丢失时不使用 INCR 创建不完整库存；清算后由受保护修复统一重建计数。

数据库墓碑必须保留到能够证明所有旧写入者不可能恢复。现在没有自动垃圾回收协议；不能为了省空间在线删除墓碑或在途元数据。恢复扫描按 SKU 游标分页，每个实例都可扫描，某行失败不阻断整批，但持续数据库故障下不保证有限时间恢复。

## 4. 对账：快照与 Redis 写回之间的并发证明

`StockReconcileJob`、warmup 和 Redis recovery 共用 `InventoryCoordinator.reconcile`，不再各自 SET。

1. A 用随机 owner 取得 Redis 租约，默认仍为 20 秒。begin Lua 检查 owner、版本存在、布局一致、在途 hash 为空，并返回版本 V。
2. A 执行 `StockFactsMapper.snapshot` 的一个 SQL 语句，获取同一一致性视图中的 A/S/O/U；不再分别 COUNT、读主库存再拼接。分段模式不把可能滞后的 master 统计重新覆盖回 segment。
3. commit Lua 在真正写入前再次检查 **当前 owner、版本仍为 V、在途仍为空、布局及数值合法**，再原子改写总库存和全部 bucket。已相等返回 UNCHANGED，不制造周期性写回。
4. finally 用 compare-delete 释放租约。旧 owner 不能删除 B 的租约。数据库/Redis 错误不转换为成功。

**实时入口交错。** 如果请求在 V 之后预扣，版本变化，拒绝写回；即使请求已经持久化并清理登记、hash 又为空，版本仍不同。若请求在 begin 前已预扣但尚未完成接纳，非空 hash 拒绝快照。故“预扣未落库”既不能消失，也不会被读时看不到的 SQL 遗漏。

**消费者交错。** 消费事务同时扣 A 并从 U 移除对应未完成责任，`A-U` 不下降；重复业务键可能一次移除多条责任，只会使预算上升。超时或最终失败只会释放责任、使预算上升。若这些事务在快照后提交，旧 E 至多偏保守，下轮修复补齐；会降低 E 的新接纳必须经过前述版本/在途协议。这也是禁止终态原地重放和活跃库存重置的原因。

**旧 owner。** 租约已失效时，无论 B 是否已经写入，A 的脚本均拒绝；校验和写入在一个 Redis 脚本里，没有 GET 检查后再 SET 的窗口。这是同一 Redis 资源上的 owner fence，不是给 MySQL、RabbitMQ 或 Redis 故障转移提供全局 fencing。

返回结果包括 APPLIED / UNCHANGED / BUSY / LEASE_LOST / VERSION_CHANGED / INFLIGHT / UNINITIALIZED / LAYOUT_MISMATCH / MYSQL_MISMATCH。冲突时跳过是正确结果，不是已经修复。持续高并发下可能一直没有合适的写回窗口，选择安全优先而非承诺可用性；正常准入仍扣现有正确预算，不等待后台租约。

## 5. 定时发送、迟到回调和超时

本地消息增加 `send_token`、`send_lease_until`。首次融合 INSERT SENDING 同时写 requestId token 和数据库时钟租约；重试/仍活跃的手工发送以单行条件 UPDATE 认领新的 UUID。扫描结果只是候选，必须同时满足状态集合、重试预算、到期时间和旧租约已过期；认领 0 行不发布、不增加重试次数。

confirm/return/发送异常携带 `requestId|token`，完成更新要求 `status=SENDING && token一致 && lease仍有效`。超时关闭、重试耗尽都在实际 UPDATE 中重新验证数据库时间、状态与租约，不能按扫描时快照夺取新任务。正常消费抢占仍与订单事务同提交，不把旧版已提交 CONSUMING 的 ABA 又引入回来。

租约不可能阻止已经恢复的旧线程向 RabbitMQ 物理发送：broker 不执行这个 token 条件。本次允许重复物理发布，依靠消费业务唯一键和事务幂等收敛；消除的是旧回调破坏新一代数据库状态，不是重复消息本身。

发送参数有行为变化：发送租约固定 20 秒；本次发送失败退避固定为数据库 NOW+5 秒，代替发送侧旧指数退避。旧 `initial-backoff` / `max-backoff` 配置不再控制这条发送路径，消费技术失败的旧配置不因此被替换。`max-retry=5` 是预算而非实测最佳值：每次成功认领重试即计一次，即使随后进程退出也消耗预算；默认融合首次发送不计重试，关闭融合时首次 claim 也计入。不可把这项变化描述成重试吞吐提升。

DEAD/TIMEOUT/CANCELLED/CONSUMED 等终态不再支持原地 replay，返回 409；批量终态重放也拒绝。补偿记录改为 WAIT_REVIEW，仅供核对，不提供重新占用预算的授权。非默认 `mq-fallback-sync=true` 拒绝启动，避免未经此协议覆盖的旁路。afterCommit 投影仍是尽力而为；订单查询以 MySQL 事实优先，缓存不是唯一事实源。

## 6. 上线和不能混用的旧操作

这是协议升级，不是兼容滚动升级。必须停止全部旧 JVM，包括暂停但还活着的进程，完成 `sql/upgrade_coordination.sql` 的一次性迁移，再启动同版节点；不能带旧 publisher callback 和旧 SET 修复者一起运行。

已有 SKU 不允许通过 `/init` 覆盖，INSERT-only 冲突返回 409。warmup 仅走受保护核对，不补充 token quota。旧数据库虽然可增加字段，但没有协调版本的旧活动会 UNINITIALIZED、停止新准入；本次不提供在旧活动运行中自动收编的迁移器。测试使用独占夹具冷重置；保留真实历史数据的部署应使用新活动/SKU，或另行设计有离线证据的迁移。

初始 MySQL 创建成功但 Redis 初始化失败时，库存行仍存在，再次 init 不能冒充重试而重置。应停写后人工核查或使用新的 SKU；本次未实现这段管理操作的跨系统原子提交。Redis 重启保留全部数据可沿用协议，**完整丢失协调 metadata 后不能凭 MySQL 可用库存直接在线重建并恢复旧 JVM**。

需要 Redis noeviction、足够内存和无外部 key 修改；native suite 检查策略但不偷偷替用户修改。无 Redis OOM/脚本运行期基础设施错误的事务回滚保证；不把“用了 Lua”泛化为任意失效下不变量仍成立。安全路径的 hash/ZSET/nonce、多 bucket 预检、SQL 事实查询都增加开销。2026-10-11 单 JVM 前后 A/B：最初的实现让入口 60 秒稳定档从 4000–5000/s 降到 3500/s，同档 Redis CPU 约为旧版 2.5 倍；微基准确认主要来自预扣脚本逐桶 TYPE+GET（单次 151–159 µs），改为一次 MGET（48–50 µs）并把确认日志降为 DEBUG 后，稳定档回到 4500/s（同轮旧版 4500–5000/s），3000/s 下单接口中位 4.4–4.9 ms（旧版 3.3–3.6），Redis CPU 约 1.8–1.9 倍。见 devcloud 证据说明最后三节；恢复时长没有测。2026-10-12 换到一台独占云服务器（16 核 32 线程；它的 50 GB 云盘约 3300 IOPS 会先限住落库，所以中间件数据放 tmpfs、MySQL 刷盘参数不变），把接纳时的清算与排队投影合并为 `coord_accept.lua`（每单入口 Redis 往返 5 → 3）后：main 对优化版交错 3 轮，入口稳定档 3000 → 3500/s，3000/s 下单接口中位 9.1–9.5 → 4.7 ms；同机改造前（`e2a6f96`）也是 3500/s，但同档 Redis CPU 仍约为改造前的 1.6–1.9 倍，3000/s 下单中位 4.6–4.8 对 2.4–2.5 ms。同机单 JVM 对三 JVM（总连接仍为 40，拆成 14/13/13）：三实例稳定档 3000、3000、2000/s，低于单 JVM 的 3500/s，3000/s 时每次借连接平均等 22–27 ms；同一台机器上拆实例不增加容量。见 `benchmark/evidence/cvm-linux-x64/README.md`。

## 7. 数字和结论变更

| 原值/原结论 | 当前实现/结论 | 原因、证据等级 |
|---|---|---|
| 对账约每分钟直接写回；租约 20s | 默认周期/租约不变，写回须 owner+版本+空在途校验 | 代码、交错单测与云端场景 01–03 通过 |
| 多次独立 MySQL 读取拼预算 | 1 个一致性 SELECT 得到事实集 | 源码路径计数，不表示数据库成本下降 |
| 预扣至 INSERT 之间无登记，异常可能直接退款 | 原子预扣登记 + 唯一键取消墓碑 + 一次清算 | 单测、真实 MySQL/Redis IT（CI run 38053832422 通过）与云端场景 04–06 |
| 发送侧状态 CAS | 行认领 token+租约，回调检查代际/DB时间 | 不承诺不重复 publish |
| 活跃 init / 终态 replay 可重新打开事实 | 409；需新准入协议或离线管理 | 保证预算证明成立，显式收缩兼容行为 |
| 历史最终对账通过、无观察到超卖/少卖 | 保持，不追改为“已发生超卖” | 原 evidence 未改 |
| 基准单元 185 | 当前 242 通过（交付环境与 JDK 17 CI）；集成 43 → 57 | 运行身份和原始输出见验证记录；非性能指标 |
| 多实例实杀验证结果无 | 同机三 JVM 九场景 9/9 通过（`1fc9e77`；首轮 5/9 的失败均为编排缺陷） | 进程信号实测，不含网络分区、故障转移和性能；见 devcloud 证据说明“多实例库存协调三 JVM 正确性复测”一节 |
| 协议性能代价未测 | 最初实现：单 JVM 入口稳定档 4000–5000 → 3500/s，同档 Redis CPU 约 2.5 倍，3000/s 下单中位约 3.5 → 8.5 ms，3500/s 负载期落库降约三成。预扣改为一次 MGET、确认日志降为 DEBUG 后（`670a096`）：稳定档 4500/s（同轮旧版 4500–5000），Redis CPU 约 1.8–1.9 倍，3000/s 下单中位 4.4–4.9 ms，3500/s 负载期落库约低一成 | 同机两次 3 轮交错 A/B，基线都是 `e2a6f96`；35 档、42 档对账全过；优化后九场景复测 9/9；Redis 内单次预扣耗时由 `INFO commandstats` 微基准测得；MGET 与日志降级各自的贡献未拆分；见 devcloud 证据说明最后三节 |
| 分桶数沿用为 Redis Cluster 设计的 128 个 | 单主部署可关闭分桶，默认不变 | 同机交错 3 轮：Redis CPU 降 25%–38%、拐点以下延迟更低，入口稳定档未提高（3500/s 时首次发送 21 对 38–41 ms，瓶颈在发布端）；不分桶九场景 9/9；见 cvm 证据说明 |
| 多实例性能未测 | 同机单 JVM 对三 JVM（14/13/13）：三实例更差 | 同机交错 3 轮：稳定档 3500 对 2000–3000/s，3000/s 借连接 0.1 对 22–27 ms；不代表多主机部署；见 cvm 证据说明 |
| 接纳时 Java 先 HGET，再带全部 bucket key 调清算脚本，再调投影脚本（3 次 Redis 往返） | `coord_accept.lua` 一次完成，只传 5 个 key；每单入口往返 5 → 3，与 PR #5 之前相同 | CI run 38201724058；新机器九场景 9/9；同机两组交错 3 轮 A/B：对 main 稳定档 3000 → 3500/s，同档 Redis CPU 降约 7%–12%；对 `e2a6f96` 稳定档都是 3500/s，Redis CPU 仍约 1.6–1.9 倍。新机器与 devcloud 的绝对数字不可横比；见 cvm 证据说明 |

## 8. 深挖时可复现的反例与源文件

- `StockCoordinationLuaTest`：旧 owner、登记进出后空表 ABA、错误类型不能写一半、单次退款及 owner 投影；接纳脚本的无退款清算与投影、不覆盖终态和他人用户锁、拒绝时保留登记。
- `InventoryCoordinatorTest`：读数据库与提交 Lua 之间插入真实脚本操作；前后边界与恢复结果。
- `CoordinationBoundaryTest`：外层事务拒绝、生产默认不能触发文件故障点、终态重放与重复初始化拒绝。
- `StockCoordinationIT`：真实 MySQL/Redis 的版本交错、unique-key 锁等待错误 1205、提交/回滚两种实际结果、多个恢复者退款一次、发送认领与旧回调、同语句事实、分段统计。固定规模用例不是容量测试。
- native 场景 02/03 区分“租约仍有效但快照被入口改旧”与“租约失效的旧 owner”；04–06 区分预扣未持久化和已经提交；09 用稀缺库存检查最终真的卖完，而不是只跑需求两倍的库存。

概念参考（用于语义核对，不是本项目实测证据）：[Redis 分布式锁](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)、[Redis Lua API](https://redis.io/docs/latest/develop/programmability/lua-api/)、[MySQL 8 一致性读](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)、[RabbitMQ 重投与幂等](https://www.rabbitmq.com/docs/reliability)。本项目 Redis 7.2.7 使用 compare-delete Lua，不使用仅新版本提供的命令。
