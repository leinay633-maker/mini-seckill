# 单实例入口容量拐点：减少借连接次数，而非扩大资源预算

基准：`64955b80a9f13a4841a2cedb32ea648a5f34b292`，代码与本轮对照 jar `deb719a` 相同，差别是证据和脚本归档。分支：`pro/capacity-knee`。**优化后的容量、延迟、CPU、落库速率及追平时间均为“待实测”。** 本文的 SQL 次数是源码路径计数，测试中的固定用户数是回归输入，均不是压测结果。

## 1. 已知事实与可检验的假设

来源：[Linux 容器实测与线程栈汇总](../benchmark/evidence/devcloud-linux-x64/README.md)。同机 32 核配额、原生中间件、100k profile、Hikari 40；unique 每次迭代先 token 再 order、持续 60 秒、库存为计划请求数的两倍。

- 已测：2000 次迭代/s 入口无错误、无丢弃；3000/s 临界，新版三轮两轮丢弃，一轮通过。每次迭代是两个 HTTP 请求，不把它写成 2000 HTTP QPS。
- 已测：3000/s 第 15 秒，200 个 Tomcat 线程中 187 个等 Hikari（活动读取 74、token 订单回源 57、消息 INSERT 32、发送标记 UPDATE 24）；第 25 秒对应四组为 87/34/24/27，共 172。不能把两个采样点都写成 187。
- 已测：CPU 合计约 18 核，MySQL Threads_running 41/42，Innodb_log_waits=0。它们说明入口被借连接等待阻塞，**不等于已定位最底层根因为池太小、磁盘或某条 SQL**；仍需看持有连接时间、事务/锁、刷盘及消费者争用。
- 已测：2000/s 以上落库可能滞后，结束后约 7–33 秒追平。入口能接受一个 60 秒突发，不等于同速率可无限期完成订单。

假设：先消除相同活动的重复读取及首次发送的冗余 UPDATE，减少入口对同一连接池的需求；不变更池大小、刷盘、消费者并发、事务边界和业务限流口径。若复测中 Hikari 等待下降但积压增长，只能认定入口瓶颈迁移，不能认定端到端容量提高。

## 2. 原值 → 新值 → 理由

| 项目 | 原值 | 新值 | 理由 / 证据级别 |
|---|---|---|---|
| 新用户成功迭代的入口同步 SQL | 5 次 | 热活动快照下 2 次，另加摊销的活动回源 | 代码路径计数；不是吞吐提升 2.5 倍 |
| 活动校验 | token、order 各 SELECT 一次 | 共享不可变元数据快照，默认 TTL 250ms，0 可关闭 | 参数选择，不是实测最优值；自然起止时间仍逐请求计算 |
| 本实例 create/start/close | 无缓存可失效 | 写命令与冷加载共用锁条带，写后 finally 失效 | 防止 close 返回后旧加载再回填 RUNNING |
| 首次消息持久化/发送 | INSERT PENDING → UPDATE SENDING → publish | INSERT SENDING → publish | 省一次 SQL/借连接及该 UPDATE 的独立提交；持久化仍先于 publish |
| token Redis 未命中 | 查 MySQL 订单 | **保留** | 未命中无法区分新用户和已丢失缓存的历史 SUCCESS/FAILED 用户 |
| Hikari 最大连接 / 消费者并发 | 40 / 8–32 | 40 / 8–32，不变 | 不混入扩容变量；也不声称已实现池隔离或公平调度 |
| 订单事务、状态 CAS、ACK 处理 | 一致性加固版 | 不变 | 不靠放松业务不变量提速 |
| 新版本拐点、p99、积压和追平 | 未运行 | **待实测** | 只以同机交错结果填写 |

热路径细分：原版为活动 SELECT ×2、订单 SELECT ×1、消息 INSERT ×1、发送 UPDATE ×1。新版保留订单 SELECT 和消息 INSERT；冷活动仍要回源。在单个持续热活动、无失效/驱逐、加载远小于 TTL 的理想条件下，250ms 对应每实例约 4 次活动加载/s，是**估算**（`1 / 0.25s` 的理论口径），不是测得的 SQL 速率或总数据库负载上界。多活动、失效、失败、慢读、事务内绕过缓存会增加回源。

不计入这张入口次数表的还有 confirm 回调、异步日志、消费者抢占、落库事务、恢复/扫描任务。不能把 5→2 写成整个系统每单总 SQL 5→2。

## 3. 活动缓存：承认一致性取舍并限制窗口

实现：`ActivityServiceImpl`，属性在 `AdmissionCapacityProperties`，沿用已有 Caffeine 依赖，无新中间件。只缓存活动是否存在、存储状态、起止时间和读开始时刻；`query` / `assertExists` 仍查 MySQL。不缓存 RUNNING 布尔结果，也不缓存可变实体。

默认最多 10000 个条目（可配 1–100000；数量限制不是堆内存字节保证），包括短期不存在快照。256 个固定锁条带合并冷加载，缓存命中不加该锁；不同 ID 哈希碰撞的冷加载会串行，这是限制锁对象数量的代价。TTL 可配 0–5 秒，默认 250ms；5 秒是防误配护栏，不是推荐关闭延迟。

同实例的 create/start/close 与冷加载使用同一条带。命令退出时即使抛错也失效；读在先则写等待其结束后再失效，写在先则后来的冷读看到新状态。正常管理接口关闭返回之后才开始校验的请求不会继续使用旧 RUNNING 快照。**此前已经通过校验、或已在执行的请求不会被撤销；没有把活动关闭与每次库存预扣串成同一个原子事务。**

其他实例关闭、直接 SQL 改状态没有失效广播：本实例可能继续使用剩余 TTL 内的旧快照。时间年龄从发起 SQL **之前**计，不从回填时算；慢读会消耗有效期，超过 TTL 的结果仅供本次在途请求使用、不进入共享缓存。到期加载失败直接报错，不返回陈旧 RUNNING。每次命中仍按当前时间校验 start/end，保留基准结束边界语义（`now.isAfter(endTime)` 才结束）。

已有 Spring 实际事务内的读取绕过共享缓存，避免发布未提交快照；生命周期写若加入外层事务，还在 afterCompletion 再失效一次。当前管理控制流本来不包外层事务；这不是多实例强一致活动门禁。要求跨实例立即关闭时先设 TTL=0，或另做有版本的集中门禁/失效协议，不能用此缓存冒充。

回归：`ActivityAdmissionCacheTest` 的自然起止、外部关闭到期、慢读、加载失败、并发冷加载、close 与回填交错、写结果不明及事务隔离用例；`InitialAdmissionIT` 的先领 token 再关闭再下单。

## 4. 首次 INSERT SENDING：融合状态写入，不异步化持久化

`SeckillProducer.initialMessageStatus()` 与 `sendInitial()` 共同决定首次协议，启动时冻结同一开关；现有入口没有包外层数据库事务，MyBatis 单次写入返回后即已提交（不能把入口另包事务后仍沿用这个结论）；`SeckillServiceImpl` 检查 INSERT 返回一行之后才调用首次发送。复用既有 mapper 的 status 参数，**没有改 SQL 允许状态集合、数据库 schema 或枚举值**。方法名 `insertPending` 是历史命名，不代表此参数必须为 PENDING。

SENDING(9) 的含义是“已持久化发送意图”，不是 MQ 已接收。PENDING→SENDING 中间过程在新首次路径里合并，无法再从消息表观察该短暂 PENDING 状态。`initial-sending-enabled=false` 可恢复原首次协议做消融。重试和管理回放继续调用原 `send()`，仍执行原状态约束 UPDATE；confirm/return 仍只能更新 SENDING，消费者仍可从 SENDING/SENT/REPLAYED 抢占。

| 切点 / 交错 | 新路径处理 | 验证入口 |
|---|---|---|
| INSERT 确定拒绝或返回 0 行 | 不 publish，沿用既有入库失败补偿 | `AdmissionCapacityTest` |
| INSERT 已提交，尚未 publish | 留下 SENDING，现有 selectRetryable / retry job 可找回 | `InitialAdmissionIT.persistedSendingWithoutInitialPublicationCanBeRecoveredByTheExistingRetryJob` |
| 同步 AMQP 异常 | 保留持久化工作，守卫更新 FAILED，默认返回排队中，不加回 Redis 库存 | `InitialAdmissionIT.synchronousPublishFailureKeepsDurableRetryWorkWithoutReturningRedisStock` |
| 消费者先提交，迟到 confirm 或发送异常 | 原状态守卫不覆盖 CONSUMED；MySQL 成功事实保持 | `InitialAdmissionIT.fastConsumerCommitWinsOverLateConfirmOrSendFailure` |
| MySQL 成功后 Redis 投影失败、ACK 失败 | 保留既有提交后投影和单次 settlement 逻辑 | 原 `OrderCommitBoundaryIT` / `SeckillConsumerTest` |

测试用独立 MySQL 连接确认 publish 调用之前 SENDING 已可见。模拟“提交后未发”的测试是不调用 publish 的确定性切点，**不是实杀进程或真实 RabbitMQ 故障演练**。

现有扫描没有按 SENDING 年龄排除刚发出的记录，可能与首次发送重叠；PENDING 旧路径也存在这个竞争。此改动不声称解决重复投递或发送代际 fencing。旧 `send()` 仍未依据 markSending 行数阻止所有迟到 publish，状态保护不等于“不再发送”。订单幂等和终态写入守卫必须继续保留。

## 5. 为什么没有删除 token MySQL 回源、没有拆池

Redis 的 SUCCESS/QUEUING 和幂等键命中可以快速拒绝；但它们缺失可能是 TTL 到期、投影失败、恢复中断或部分数据丢失，**不是新用户证明**。数据库唯一键最终能防止重复 MySQL 订单，不代表新发 token 后重复预扣的 Redis 库存/资格自动归还。当前 duplicate reconciliation 主要收敛订单与消息事实，不能把它当成任意重复 reservation 的完备清算协议。因此保留该 SELECT，也不加负缓存或未经证明的 Bloom 完整性假设。SUCCESS、持久化 FAILED、数据库异常和 Redis 恢复门禁均有回归。

本轮不扩大 40 连接，不按经验拆成两个池。隔离会引入预算如何划分的未知量；拆 datasource 还必须保证订单、库存、消息完成绑定同一个事务资源。不增加预算的分池也可能把空闲连接困在另一池中。这里先减少两个入口的无效需求，保留消费者可借用的同一预算。若复测仍饥饿，下一步应基于 Hikari 使用/等待时间、各阶段速率与积压量做**固定总预算**的隔离/并发限制实验，而不是默认改成 80/160。

## 6. 一致性不变量和范围

[CONSISTENCY-REVIEW.md](CONSISTENCY-REVIEW.md) 第 4 节五条不变量不变：业务唯一性/库存守恒；订单、扣库存、消息完成同事务回滚；CONSUMED 关联真实 SUCCESS；旧自动写入不能覆盖受保护状态；ACK 失败不能反写已提交成功或二次 settlement。`OrderServiceImpl`、`SeckillConsumer`、mapper SQL、重试/恢复 job、索引、刷盘策略均未修改。

新增测试不替换原测试，不降低 45% JaCoCo 门槛或非跳过集成门禁。仍保留的缺口：Redis 预扣到消息持久化的进程退出/提交结果不明窗口（沿用的异常补偿并不因此被证明安全）、发送/消费 ABA 与无 generation fencing、多实例恢复屏障、非默认 mqFallbackSync 兼容入口。**这些不属于本次“已解决”。**

## 7. 诊断与复测判定

增加有限标签的 `seckill_capacity_stage` Timer：`activity_lookup`、`token_order_lookup`、`message_insert`、`initial_publish`。前三项计时含借连接等待、数据库往返和客户端处理，不是数据库内部 SQL 执行时间。最后一项在关闭融合时包含 markSending。Timer 记录次数可校验路径，但不假定其默认提供 p99 直方图。`seckill_activity_cache_total` 区分 hit/miss/disabled；并发等待冷加载的请求按初次 miss 计数，**miss 数不等于 SQL 数**。新指标异常尽力隔离，不让 INSERT 之后的计时故障误入补偿。

复测需同时保存入口错误/丢弃、token/order 分路由延迟、Hikari active/pending/acquire/usage、线程栈、MySQL 状态、队列 ready/unacked、每秒 SUCCESS 订单数及追平时间。没有抓到的指标写“未采集”，不补画数据。程序内计时、k6 admission 延迟、最终提交速率是不同量。

阶梯、交错与消融命令见 [CAPACITY-RETEST.md](CAPACITY-RETEST.md)。只有在同机多轮更高档位通过、最终一致性通过且延迟/积压代价可说明后，才填写“改完到多少”。若仅减少 SQL 而拐点没变，应如实记录无显著改善。

## 8. 验证记录

代码及回归已实现；当前编辑环境 `mvn -B clean verify` 无法启动（`mvn: command not found`），不是 Java 测试通过或测试失败的记录。`bash -n benchmark/native-linux/ab-rounds.sh` 通过。PR 的 CI 编译、单元/真实 MySQL 集成结果另行追加；当前没有新版本压测、真实 MQ/Redis 故障或端到端吞吐数据。
