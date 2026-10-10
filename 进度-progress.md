# Mini 秒杀高并发下单系统 进度

按时间追加，最新的在最下面。

### 2026-10-09 13:33 · 本地 Claude Code · main
- 改动：建立 AGENTS.md（进度规则）和本进度文件；当前最新提交 91cc11d（2026-07-21，docs: 归档多实例故障积压和长跑可靠性证据）。
- 验证：未验证（只加文档）。
- 未完成 / 下一步：无。

### 2026-10-09 15:33 · GPT-6 Pro · pro/interview-grade
- 改动：基于 68858ab 修复 MySQL 提交前 Redis 成功投影、ACK 异常误入业务补偿、唯一键冲突/恢复误判成功、失败订单持久化与消息终态不原子、旧重试快照覆盖状态等问题；涉及 OrderServiceImpl、SeckillConsumer、SeckillMessageMapper 与恢复/重试任务，补充真实 MySQL 事务及并发回归、证据归档脚本和 CI。README 与 docs/CONSISTENCY-REVIEW.md、CONSISTENCY-EXPERIMENTS.md、CI-VALIDATION-20261009.md 记录反例、取舍、证据分级和实验方案；REPORT.md、REPORT-RELIABILITY.md 历史实测数字未改，obsidian-vault 未改。已创建 PR #1，只推 pro/interview-grade。
- 验证：代码提交 c8668c7ec6d6aa3f14501f5c2438c570ac6761df 的 GitHub Actions run 37898578944 两个 job 均成功（Ubuntu/JDK 17）；mvn -B clean verify：112 单元测试通过 / 0 失败 / 0 错误 / 0 跳过；mvn -B clean -Pintegration-test verify：同一批 112 单元及 23 集成通过 / 0 失败 / 0 错误 / 0 跳过，其中 OrderCommitBoundaryIT 20 项；非跳过门禁 sources=3 reports=3 tests=23，现有 45% JaCoCo 门槛、6 组 Compose 配置、131 文件/37 summary JSON 证据检查通过。当前编辑环境 python3 -m unittest discover -s scripts -p 'test_consistency_evidence.py' -v：8 通过 / 0 失败 / 0 跳过；python3 -m py_compile scripts/*.py 通过。当前环境未运行 Maven、Docker 演练或 k6；最后的文档提交以其 PR 检查结果为准，代码验证快照见 docs/CI-VALIDATION-20261009.md。
- 未完成 / 下一步：本机拉取 PR 分支跑 mvn 测试；新版本性能、真实 ACK 丢失/Redis 投影故障、积压和长跑均待本机实测，是否执行先问用户，不把 CI 或旧报告代替复测。Redis 预扣到消息落库窗口、消费代际 fencing、多实例恢复屏障仍待设计验证；mqFallbackSync 保持默认 false，非默认兼容入口仍需独立回归。核对最终分支 SHA 与 CI 后再合并；合并后才按新内容更新 obsidian-vault 面试案例稿。

### 2026-10-09 15:47 · 本地 Claude Code · main
- 改动：核对 PR #1（pro/interview-grade，09906d2）的代码 diff、PR 描述和 docs/CONSISTENCY-REVIEW.md、CONSISTENCY-EXPERIMENTS.md，全部采纳，fast-forward 合并到 main；本机新装便携 JDK 17.0.20.1 与 Maven 3.9.9（C:\tools，未改系统 PATH）。
- 验证：本机 Windows/JDK 17 `mvn -B clean verify`：112 单元测试通过 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS；`python -m unittest discover -s scripts -p test_consistency_evidence.py`：8 通过。集成测试（-Pintegration-test，Testcontainers）本机无 Docker 未跑，以 CI run 37899792451 为准（112 单元 + 23 集成通过）。新版本 k6 压测与真实故障演练未执行。
- 未完成 / 下一步：PR 描述中的“待本机实测”实验（同机性能对照、提交后 ACK 丢失、提交后 Redis 投影失败、multi/backlog/soak 复测）需 Docker 与 k6，执行前先问用户。另记一处待核风险（按 Spring AMQP MANUAL 模式语义推断、未实测）：recordFailedOrder / reconcile / markDeadFromConsuming 抛错时消息既不 ACK 也不 NACK，会一直占用 prefetch（默认 50）直到通道重建，数据库长时间故障时可能让消费者停摆，靠 stale CONSUMING 恢复和重投兜底。

### 2026-10-09 17:02 · 本地 Claude Code · main
- 改动：在 32 核 Linux 开发容器上（无 Docker，MySQL 8.0.46 / Redis 7.2.7 / RabbitMQ 3.13.7 均为原生进程）做单实例容量摸底和新旧版交错对照；原始证据放入 benchmark/evidence/devcloud-linux-x64/（README 含环境、方法、全部结果和拐点线程栈汇总），搭建与压测脚本放入 benchmark/native-linux/。历史 REPORT 未改。
- 验证：k6 unique 恒定到达率每档 60 秒，共 14 档次（新版 deb719a、旧版 68858ab 各 3 轮）；全部对账一致（订单数 = 入队数，重复订单组 0，订单号重复 0）。2000/s 无错误无丢弃；3000/s 三次中两次出现丢弃（一次伴随 10% token 接口超时）。3000/s 时 jstack 显示 200 个 Tomcat 线程中约 187 个在等 Hikari 连接（池 40），MySQL Threads_running 41，CPU 合计约 18 核未打满。新旧版未测出性能差异（版本内波动大于版本差）。mvn 与集成测试本轮未重跑。
- 未完成 / 下一步：针对连接池瓶颈的优化待做（交给 GPT-6 Pro），优化后在同一容器按同样阶梯与交错方式复测；提交后 ACK 丢失、Redis 投影失败的精确切点演练未做；4000/s 以上与多实例未测。

### 2026-10-09 17:38 · GPT-6 Astra Pro · pro/capacity-knee
- 改动：基于 64955b8 为活动入口加入默认 250ms 的有界元数据缓存（本机生命周期失效、冷加载合并、按 SQL 发起时刻计龄、事务内不共享快照），融合首次本地消息 INSERT SENDING 与发送，保留 token 的 MySQL 事实兜底及 Hikari 40 连接预算；添加独立消融开关、分阶段诊断、缓存/入口/真实 MySQL 回归，ab-rounds 支持指定两版本及防覆盖运行目录。docs/CAPACITY-KNEE.md 和 CAPACITY-RETEST.md 记录原值→新值→理由、关闭窗口、保留风险和同机复测步骤；历史证据、REPORT、订单事务/消费者/mapper SQL、main 与 obsidian-vault 未改。
- 验证：当前编辑环境 `bash -n benchmark/native-linux/ab-rounds.sh` 通过；`mvn -B clean verify` 无法启动，具体错误 `mvn: command not found`，Java 单元与集成尚未验证，交由本 PR CI 检查后追加结果。已核对 SeckillServiceImpl 完整基准 blob 和改动后的远端 blob 与本地字节 SHA 一致。5→热缓存下2次仅为入口同步 SQL 代码计数，无新版本压测数据。
- 未完成 / 下一步：核对 PR CI 编译、单元与非跳过 MySQL 集成门禁；本机拉分支跑 mvn，云端与 deb719a 同阶梯交错复测并更新“待实测”；性能拐点、真实 MQ/Redis 故障、持续落库能力仍待实测。复测对账后再合并与更新面试稿，不在本分支写性能提升数字。

### 2026-10-09 17:47 · GPT-6 Astra Pro · pro/capacity-knee
- 改动：PR #2 的功能提交 38347fc 已通过 CI；新增 docs/CAPACITY-CI-VALIDATION.md 归档运行、检出/tree 身份、测试分项与原始 artifact 校验值，本次仅追加文档，不改功能代码或历史条目。
- 验证：GitHub Actions run 37912946477 两个 job 均 success（Linux/JDK 17.0.20.1）；`mvn -B clean verify`：146 单元通过 / 0 失败 / 0 错误 / 0 跳过；`mvn -B clean -Pintegration-test verify`：同一批 146 单元与 32 集成通过 / 0 失败 / 0 错误 / 0 跳过，其中 InitialAdmissionIT 9、OrderCommitBoundaryIT 20。非跳过 IT 门禁、45% JaCoCo、Python/Compose/原始证据检查通过。已解析 Surefire/Failsafe XML 与 summary/Maven 日志，两份下载 ZIP 的 SHA-256 与 GitHub digest 一致，consistency-evidence 内 287 项文件哈希全部匹配。CI 临时合并 bcd7598 的 tree 与 38347fc 完全相同，不代表已合并 main；最终文档提交的 CI 以 PR 最新检查为准。
- 未完成 / 下一步：本机跑 mvn，云端按 docs/CAPACITY-RETEST.md 与 deb719a 交错复测。改后容量、延迟、CPU、持续落库、积压和追平均待实测；未做真实 MQ/Redis 故障或实杀进程。核对远端最终 SHA 与 PR 检查后交接，不合并、不改 obsidian-vault。

### 2026-10-09 20:27 · 本地 Claude Code · pro/capacity-knee
- 改动：核对 PR #2（82ec268）代码 diff、三份 CAPACITY 文档与 PR 描述，全部采纳；在云研发容器构建 capacity-82ec268.jar，按 docs/CAPACITY-RETEST.md 做两组新旧交错 A/B（AB_RUN_ID capacity-20261009-180011 与顺序反转的 capacity-rev-20261009-182937，各 3 轮，阶梯 1000–5000）、四组开关消融（abl-capacity-82ec268-20261009-190815，2 轮）和 3000/4000 拐点线程栈。原始数据放 benchmark/evidence/devcloud-linux-x64/capacity-82ec268/（297 个文件），README 追加“入口连接需求改造复测”章节，CAPACITY-RETEST/KNEE 的待实测格已填。新增旁路采样与汇总脚本 metrics-sampler.py、disk-sampler.py、ablate-rounds.sh、ab-summary.py、retest-table.py、timeline.py、jstack-classify.py、jstack-borrowers.py、stuck-messages.sh；历史表与 REPORT 未改。
- 验证：本机 Windows/JDK 17 与容器 Linux/JDK 17 `mvn -B clean verify` 各 146 单元通过 / 0 失败 / 0 错误 / 0 跳过；集成测试两处都无 Docker 未跑（CI run 37913939462 已过）。压测：低磁盘卡顿档里默认配置 4000/s 3/3 通过、5000/s 3/3 丢弃 5.6%–7.4%；deb719a 与新 jar 两项关闭 4000/s 共 5 次全部失败（系统错误率 33%–56%）。3000/s：p99 53–63 ms 对 229–450 ms，负载期 SUCCESS 2310–2556/s 对 1313–1392/s，追平 5–9 秒对 20–23 秒；两版本都跟不上 3000/s 持续到达，入口放量越大负载期落库越慢（5000/s 约 1280/s）。消融显示收益主要来自首次融合。全部档业务键重复 0、订单号重复 0。宿主数据盘写入卡顿导致 7 档失败，3 档无磁盘数据无法归因，均原样保留。
- 未完成 / 下一步：复现既有消费者缺陷——SeckillConsumer.tryMarkConsuming 在 try 外，连接池超时抛出后 MANUAL 模式不 ACK/NACK，消息停在 SENT、最后被判 TIMEOUT；CannotCreateTransactionException 被直接判 DEAD。待修复并加回归。2000–3000/s 持续完成上限未细分；固定 40 连接总预算的入口/消费者隔离实验、多实例、ACK 丢失与 Redis 投影切点演练未做。

### 2026-10-09 20:36 · 本地 Claude Code · main
- 改动：PR #2（pro/capacity-knee）核对后全部采纳，fast-forward 合并到 main（含复测证据提交 b9c6c8c）；PR 描述的待实测部分已改为同机复测结果。obsidian-vault 面试案例稿同步新增第十二节（Q50–Q55：容量拐点、为何不调大连接池、活动缓存取舍、A/B 与消融、端到端持续完成能力、消费者不 ACK 缺陷）。
- 验证：b9c6c8c 的 GitHub Actions run 37930206031 两个 job（test、integration-test）均成功；本机 `scripts/check-evidence.sh` 通过（37 个 summary、131 个必需文件），`python -m unittest discover -s scripts -p test_consistency_evidence.py` 通过。
- 未完成 / 下一步：修复 SeckillConsumer 抢占步骤在 try 外导致的不 ACK/NACK 与开事务失败直接判 DEAD，并加连接池超时的回归；固定 40 连接总预算的入口/消费者隔离实验；2000–3000/s 持续完成上限细分。

### 2026-10-10 12:31 · GPT-6 Astra Pro · pro/consumer-pool-budget
- 改动：从 PR #3 的 e29e452 断点继续，核查已提交的消费者事务抢占/显式结算与固定总连接预算隔离；补齐 pool-budget-suite 一键后台同机对照、23 组/143 个计划档位（A/B 交错、同版共享/12+28/20+20 消融、独立 60 秒拐点诊断），沿用原生 cap-step 与 k6 阶梯；新增数据库与 MQ 排空门禁、停机后清旧夹具、分池指标、预定磁盘分组、全量含失败/未运行格的汇总及哈希证据包。完善 app 停机/PID 防重叠，修正 SQL 采样时间与 pool 标签覆盖；新增离线测试并加入 CI、docs/CONSUMER-POOL-BUDGET.md，历史报告和证据不改。
- 验证：本编辑环境 `python3 -m unittest discover -s scripts -p 'test_pool_budget_benchmark.py' -v`：30 通过 / 0 失败 / 0 错误 / 0 跳过；相关 Python 编译、4 个 shell 脚本 `bash -n` 通过。已下载并核对断点 e29e452 的 CI run 37934795603 原始 XML：185 单元、42 集成全部通过 / 0 失败 / 0 错误 / 0 跳过（ConsumerPoolBudgetIT 10）；artifact 11618195753 SHA-256 与 GitHub digest 一致，详见文档。编辑环境无 Maven，未本地运行 Java；新增脚本的最终远端 CI 以 PR 最新检查为准，内网性能复测未运行。
- 未完成 / 下一步：核对最终分支 SHA 与 PR CI，本机在原 Linux 容器执行 `bash benchmark/native-linux/pool-budget-suite.sh start`，跑完一次性汇总全部样本并脱敏证据，再决定合并、更新面试稿 Q54/Q55。12/28、20/20 仅估算实验参数，不承诺吞吐提升；新排空尾部包含 5 秒安静保护，不可直接与旧尾部秒数比较。业务 10 分钟超时、长期故障、Redis 预扣窗口及多实例 fencing 仍不在无条件保证范围内。

### 2026-10-10 16:18 · 本地 Claude Code · pro/consumer-pool-budget
- 改动：核对 PR #3（33b17be）代码 diff、docs/CONSUMER-POOL-BUDGET.md 与 PR 描述，全部采纳；在云研发容器（/data 仍挂 vdc，未覆盖 DISK_DEVICE）用 `bash benchmark/native-linux/pool-budget-suite.sh start` 一次后台跑完 23 组（12:58–16:04，中途未干预）。证据脱敏（容器主机名替换）、去掉 jars/sources、日志 gzip 后放入 benchmark/evidence/devcloud-linux-x64/pool-budget-33b17bed/（1935 个文件，16 MB）；evidence README 追加本轮章节，CONSUMER-POOL-BUDGET.md 填第 6 节待复测两行并加第 8 节。历史表、REPORT 未改。
- 验证：CI run 38024987654（headSha 33b17be）两个 job 成功。原始包 SHA-256 74b66c49…ace3a 本机校验与 sidecar 一致。计划 143 档：执行 124、未执行 19；执行档对账全部通过（TIMEOUT 0、DEAD 0、重复 0、40 预算校验通过），2 档磁盘 noisy 保留。入口稳定档 baseline 与 shared 均 4000 过 / 5000 挂；split28 4000/s 0/4（系统错误 11.8%–22.5%）；split20 3 轮中 2 轮在 2600 或 4000/s 超 1% 丢弃。负载期 SUCCESS/s：3000/s shared 2959–2974 对 baseline 2559–2629（跨阶段比较），4000/s 2222–2514 对 1640–1892；split20 4000/s 3817–3882、零积压。consume deferred 0 次，消费者连接饥饿本轮未触发。本机 Windows `PYTHONUTF8=1 python -m unittest`：test_pool_budget_benchmark 23 通过 / 7 跳过（shell 用例 Windows 跳过；不设 UTF-8 时有 1 个 GBK 读文件 error，与改动无关），test_consistency_evidence 8 通过；`scripts/check-evidence.sh`（python3 转发到 python）通过。Maven 本机未重跑，以 CI 为准。
- 未完成 / 下一步：split20 入口稳定性需更多轮次及 16/24、24/16 细分；消费者连接饥饿的现场故障注入未做；发送侧 SeckillMessageRetryJob 每 15 秒 50 条的补发速度在 baseline 3000/s 一档主导了 180 秒尾部，各组都有 49–1361 次重试重发，消息停在哪个发送状态未分状态采样；多实例、soak、ACK 丢失与 Redis 投影切点演练未做。

### 2026-10-10 16:22 · 本地 Claude Code · main
- 改动：PR #3（pro/consumer-pool-budget）核对与同机复测后全部采纳，fast-forward 合并到 main（含复测证据提交 bf4f2d8），PR 已评论复测摘要。obsidian-vault 面试案例稿第十二节 Q54、Q55 按复测结果改写（单事务化与拆池结果、修复的证据层级与边界、发送侧重试补发现象），代码定位补两条；该稿未提交。
- 验证：bf4f2d8 的 GitHub Actions run 38037448811 两个 job（test、integration-test）均成功；GitHub 显示 PR #3 已合并。
- 未完成 / 下一步：同上一条；另外容器 /data/ms/mini-seckill 当前停在 pro/consumer-pool-budget 分支（33b17be），下次复测前切回 main 并拉取。

### 2026-10-10 16:41 · 本地 Claude Code · consumer-claim-interleaving
- 改动：PR #4，只加测试和文档、不改业务代码。ConsumerPoolBudgetIT 新增 inFlightClaimIsInvisibleToRecoveryAndTimeoutCasWaitsThenChangesNothing：消费者抢占后暂停在事务中，验证外部读到 SENT、stale CONSUMING 恢复查询为空、重复投递行锁超时后 NACK 重投、超时任务按 SENT 快照发起的 CAS 等到提交后改动 0 行且不删 Redis 幂等键；setUp 抽出 session()，pool() 支持指定锁等待秒数。CONSISTENCY-REVIEW 3.3 追加消费侧 ABA 已由单事务抢占消除的更新、P1 行改为只剩发送侧迟到 confirm 回调；README 两处同步。第一次提交用 information_schema.innodb_trx 判断 LOCK WAIT，CI 上一直读到 0（超时任务连接确实卡了 8 秒，原因未查清），改为记录 CAS 开始与返回时刻判断。
- 验证：本机 Windows/JDK 17 `mvn -B clean verify`：185 单元通过 / 0 失败 / 0 错误 / 0 跳过；`scripts/check-evidence.sh` 通过。CI run 38038330758（b8642c1）integration-test 失败 1 项（上述检测方法）；修正后 run 38038615216（72e75ab）两个 job 成功，Failsafe XML：ConsumerPoolBudgetIT 11 项全过，集成合计 43、单元 185，0 失败 / 0 错误 / 0 跳过。
- 未完成 / 下一步：多实例定时任务安全（StockReconcileJob 用租约 + fencing 令牌写 Redis，重试/超时任务按行认领），随后容器上 2–3 实例复测；发送侧迟到 confirm 回调代际；Redis 预扣到消息落库窗口。滚动升级期间旧版本实例仍会单独提交 CONSUMING。

### 2026-10-10 16:50 · 本地 Claude Code · main
- 改动：PR #4（consumer-claim-interleaving）fast-forward 合并到 main（2347c01），GitHub 显示已合并。obsidian-vault 面试案例稿 Q48 结尾改为“取消已提交的消费中状态而非加代际号”、交错测试内容与两个边界（滚动升级旧实例、发送侧迟到回调）；该稿未提交。上一条 16:41 写于最后一次文档提交之前，本条补记其后的验证与合并。
- 验证：最终提交 2347c01 的 GitHub Actions run 38038789658 两个 job（test、integration-test）均成功。
- 未完成 / 下一步：同 16:41 条目。

### 2026-10-10 16:56 · 本地 Claude Code · main
- 改动：为交给 GPT-6 Pro 做多实例协调，把 PR #3 复测 23 组 app.log.gz 里的 StockReconcileJob 告警原文抽成 benchmark/evidence/devcloud-linux-x64/pool-budget-33b17bed/reconcile-during-load.txt（连接器读不了 gz）。每组压测期间对账任务约每分钟改写一次 Redis 库存（主矩阵每组 6–13 次），并伴随 soldCount 与 successOrderCount 不一致告警；当时库存为需求 2 倍、MySQL 有库存兜底，最终对账全部通过。另在容器核实：非特权（iptables 权限不足、无 tc）、无 nginx/haproxy/socat，GitHub release 直链可下载。
- 验证：抽取文件 334 行，逐组计数与各组 app.log.gz 的 grep 计数一致；不含容器主机名。未运行测试（只加证据文本）。
- 未完成 / 下一步：等 Pro 的 PR（多实例下后台任务与 Redis 库存修复的正确性、一键多实例复测）；容器 /data/ms/mini-seckill 仍停在 pro/consumer-pool-budget，复测前切回 main 并拉取。

### 2026-10-10 20:01 · GPT-6 Astra Pro · pro/multi-instance-coordination
- 改动：从 PR #5 的 8ca5ec0 断点继续，实现预扣与在途登记/版本同 Lua、MySQL 唯一键取消墓碑清算、同语句库存事实及 owner+版本+空在途写回校验；发送按行认领 token/DB 租约及迟到回调防护，超时在 SQL 重新检查，禁止活跃库存重置/终态原地重放/同步 fallback/Cluster 旁路。增加真实 MySQL+Redis 交错 IT 源码、单元/编排回归和原生三 JVM 一键后台九场景脚本；补三份 MULTI-INSTANCE 文档、README 与 CI，历史 REPORT 和既有 evidence 未改，未修改 obsidian-vault。
- 验证：编辑环境 Linux/OpenJDK21.0.12.1（release17），离线 `mvn -o -B -Dmaven.repo.local=/mnt/data/build-tools/repository clean verify`：242 单元通过/0失败/0错误/0跳过，45% JaCoCo 门禁通过；新 test_coordination_suite 34通过，旧 test_consistency_evidence 8通过；Python编译、bash -n、git diff --check、历史证据131文件/37JSON检查通过。旧 test_pool_budget_benchmark 运行30项，两个cap-step 10s超时error，在精确8ca5ec0基准worktree复现同样2error，未改旧门禁。新14项展开IT只编译未执行；JDK17 CI和云机三JVM未验证，不能引用旧断点CI冒充新结果。完整日志摘要见 docs/MULTI-INSTANCE-VALIDATION.md。
- 未完成 / 下一步：仓库权限已核实push=true，远端PR5仍8ca5ec0；本环境git直连/dry-run错误为 Could not resolve host: github.com，不是账号只读。提交后重试真实分支推送；写不通则交付可直接应用并保留提交身份的bundle/补丁。导入后审diff、跑JDK17与非跳过IT CI，再在原独占云机运行 `bash benchmark/native-linux/coordination-suite.sh start`，一次性核对九场景完整证据后才合并、更新面试卡片。协议需全旧JVM冷切换；元数据丢失、Redis故障转移/驱逐、网络分区、混合版本及新性能数字均未认证。

### 2026-10-10 20:02 · GPT-6 Astra Pro · pro/multi-instance-coordination
- 改动：业务代码已本地提交 d0788597c88037d32ad4cb67375cc714cf734a26；本次仅追加写入诊断。为无法直推的交付准备增量 Git bundle、补丁、PR 描述和原始验证日志，不改 main 或其他仓库。
- 验证：对指定分支进行两次实际 `git push`（常规 origin、显式 HTTPS 并关闭本命令代理）均 exit128：`fatal: unable to access 'https://github.com/leinay633-maker/mini-seckill.git/': Could not resolve host: github.com`；getent DNS失败，socket为Temporary failure in name resolution。GitHub连接器再次核实账号push/admin权限为true，PR5仍draft、远端head为8ca5ec0eddf2a8a8ac21c9d6110e53e988835c1a，base仍e2a6f96。前一条242单元/34新编排/8校验结果对应本次未再修改的业务代码，JDK17远端CI仍未验证。
- 未完成 / 下一步：不是“PR已经交付”或“连接只读”；先从交付bundle在本机按8ca5ec0前置提交快进导入并仅推pro/multi-instance-coordination，再核对远端最终SHA、更新PR描述与CI。之后依复测手册在独占云容器后台跑九场景，保留失败/unknown/not_run和完整证据，核对后才合并及更新面试稿。

### 2026-10-10 20:46 · 本地 Claude Code · pro/multi-instance-coordination
- 改动：从 Pro 交付包 mini-seckill-multi-instance-2288ced.zip（SHA-256 659da5fffafcdcb22aba60cbd9bd99a9781e91ec02c17f1fe5b58d63264d28c5；外部 .sha256 附件已被临时目录清掉，改以包内 SHA256SUMS 核对 87 个文件全部一致）的 coordination.bundle 按 8ca5ec0 前置提交快进导入 2288ced，只推本分支（8ca5ec0→2288ced，未强推、main 未动），远端核对为完整 SHA，PR #5 描述替换为包内 PR_BODY.md。2288ced 的 CI integration-test 失败：本 PR 未改的 OrderCommitBoundaryIT 两条断言（:237、:267）仍假定刚以 SENDING 插入的行立即可被重试扫描到，而新 insertPending 会写入首发线程 20 秒发送租约，扫描按设计跳过。追加只改测试的 f86c1d5：先断言租约有效时扫描为空，再让租约过期后继续原场景；生产代码未改。审 diff 未发现需改的协议问题；另注意到场景 02 暂停 A 后只等 10 秒等新订单成功，若该消息恰好投给 A 的消费者，要等 broker 按心跳判定连接断开后才重投，可能出现与协议无关的超时（推断，待复测结果核对）。
- 验证：CI run 38052130288（2288ced）test 成功、integration-test 失败（OrderCommitBoundaryIT 20 项中 2 失败，StockCoordinationIT 14 项通过）；本机 JDK 17 `mvn -B -q test-compile` 通过（本机无 Docker，IT 未在本机跑）；CI run 38052834059（f86c1d5）两个 job 成功：Surefire 35 份 242 单元、Failsafe 57 项集成（含 StockCoordinationIT 14），均 0 失败 / 0 错误 / 0 跳过；Python test_consistency_evidence 8、test_pool_budget_benchmark 30、test_coordination_suite 34 均 OK（Pro 环境的两个 cap-step 超时在 CI 未出现），JaCoCo 门禁与 check-evidence 通过。
- 未完成 / 下一步：云容器 /data/ms/mini-seckill 已从 pro/consumer-pool-budget 切到本分支 f86c1d5（工作树干净；启动前无 java/k6 进程、无暂停进程、18080–18083 空闲、Redis 7.2.7 noeviction），20:44 执行 `bash benchmark/native-linux/coordination-suite.sh start`，worker PID 475306，证据目录 /data/ms/results/coordination-20261010-204430-f86c1d55-475168；跑完一次性核对九场景与全部 failed/unknown/not_run 后再决定合并、更新面试稿。

### 2026-10-10 21:12 · 本地 Claude Code · pro/multi-instance-coordination
- 改动：云容器首轮复测（f86c1d5，coordination-20261010-204430-f86c1d55-475168，20:44–20:50）5/9：01/08/09 在 k6 正常结束（exit 0、系统错误率 0）后因 validate_load 按 handleSummary 的 `values` 层级读 `--summary-export` 抛 KeyError，未执行排空与停机后的最终校验；02 在 A 暂停后等新订单成功只给 10 秒，消息投给了暂停 A 的消费者（10 秒时 SUCCESS 0、unacked 1）而超时，清理时 A 写回返回 VERSION_CHANGED、剩余 9。原始数据中没有协议违例，崩溃时观测已满足最终断言，但不计为通过。修正 1fc9e77（只改 coordination-suite.py 与其离线测试）：两种 k6 布局都接受并用首轮实际导出字段补回归测试；02 的 A 修复租约 60 秒、02/03 成单等待 45 秒。重跑后两轮证据瘦身入库 benchmark/evidence/devcloud-linux-x64/coordination-1fc9e77a/（299 个文件，2.25 MB）与 coordination-f86c1d55/（270 个，2.06 MB），省去 jar/源码快照/JaCoCo HTML/worker.pid，日志类文本 gzip，host.txt 主机名替换；证据 README 追加本轮章节，MULTI-INSTANCE 三份文档与 README 同步状态。
- 验证：本机 `PYTHONUTF8=1 python -m unittest` test_coordination_suite 36 通过 / 4 跳过（Windows 跳过信号与 shell 用例），修正后的 validate_load 对首轮 01/08/09 的真实 k6 摘要均通过；CI run 38053832422（1fc9e77）两个 job 成功：242 单元、57 集成 0 失败 / 0 错误 / 0 跳过，Python 8 / 30 / 36 OK。重跑 coordination-20261010-205952-1fc9e77a-485818（20:59–21:06）DONE.json status=passed、9/9、matrix_completed=true；原始包 SHA-256 b1ca0f2a…e31957 与 sidecar 一致，解包后包内 3182 个文件核对通过（首轮 c9d1fd14…7a3f3c、3153 个同样通过）。逐场景：01 注入后 11 ms 节点 C APPLIED（请求窗口内），15001 = 14999 排队 + 2 售罄，253 次对账中 APPLIED 1、INFLIGHT 108、VERSION_CHANGED 98、UNCHANGED 39、BUSY 7；02 VERSION_CHANGED；03 LEASE_LOST 且锁仍属 B，702 的消息约 13.7 秒后才由 broker 重投；04/05 墓碑退款一次、旧 INSERT 撞唯一键；06 已提交不退款、补发后 CONSUMED；07 迟到确认 changed=0；08 SUCCESS 15001；09 SUCCESS 恰好 41；九组停机后终检全过，无 unknown / not_run / 证据写入错误。
- 未完成 / 下一步：推送后核对最终 head 的 CI，再 fast-forward 合并到 main 并在 PR 评论复测摘要，然后更新 obsidian-vault 面试稿相关卡片。未覆盖：多主机、网络分区、Redis/MySQL 故障转移、协调元数据丢失与驱逐、新旧版本混跑、任何性能数字；`OrderCommitBoundaryIT` 仍测试已无生产调用的旧发送侧写法（markFailed/markFailedForRetry/markSending/markDead 与 createOrderFromMessage），可另行清理。

### 2026-10-10 21:18 · 本地 Claude Code · main
- 改动：PR #5（pro/multi-instance-coordination）核对 diff、CI 与云容器三 JVM 复测后采纳：标为 ready 后 fast-forward 合并到 main（bbb167e，含本机的 IT 断言修正 f86c1d5、编排修正 1fc9e77 与两轮证据提交），GitHub 显示 PR #5 已合并，PR 已评论核对与复测摘要。云容器 /data/ms/mini-seckill 停在 pro/multi-instance-coordination（1fc9e77），两轮原始包留在 /data/ms/results/。
- 验证：bbb167e 的 PR 触发 run 38054864332 与 main 推送触发 run 38055048618 两个 job（test、integration-test）均成功。
- 未完成 / 下一步：更新 obsidian-vault 面试案例稿的相关卡片；多主机、网络分区、Redis/MySQL 故障转移、协调元数据丢失与驱逐、新旧版本混跑和性能影响均未测；协调墓碑没有自动清理；OrderCommitBoundaryIT 里只剩测试在用的旧发送侧写法可另行清理。
