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

### 2026-10-10 23:59 · 本地 Claude Code · main
- 改动：docs/MULTI-INSTANCE-VALIDATION.md 把交付记录里的“45% 指令门禁”更正为行覆盖门禁（pom 规则 LINE COVEREDRATIO ≥ 0.45，行 1800/1076 约 62.6%）。obsidian-vault 面试案例稿按 PR #5 改写：Q2/Q5/Q12/Q14/Q16/Q18/Q22/Q24/Q31/Q34/Q35/Q36/Q39/Q43/Q44/Q48/Q49 的旧口径（逐桶 Cluster 回退、INSERT 失败直接加回库存、死信原地回放、指数退避、超时无条件删幂等 key、Redis 丢失后在线重建、“没有请求级账本/代际令牌”等）改为现行协议与复测事实，新增第十三节 Q56–Q59（预扣在途与墓碑、发送代际、验证方法与首轮失败、协议代价），代码定位补协调相关文件，共 59 卡 13 节；该稿未提交。
- 验证：面试稿卡号 1–59 连续无重复、13 节，旧口径关键词复查只剩有意保留的历史表述；仓库本条只改文档，未跑测试。
- 未完成 / 下一步：面试稿定稿要按 Agent后端面试笔记蒸馏 的规则写进上传正文；容器 /data/ms/mini-seckill 停在 pro/multi-instance-coordination（1fc9e77），下次复测前切回 main 并拉取。

### 2026-10-11 00:07 · 本地 Claude Code · main
- 改动：无代码改动，记录一次偶发 CI 失败。e75ff02（只改文档）的 run 38065842025 第 1 次 integration-test 失败：OrderCommitBoundaryIT.concurrentDuplicateDeliveriesCreateOnlyOneBusinessOrder 中 12 条同用户、不同请求号的消息并发 INSERT seckill_order，一个事务被 InnoDB 判为死锁牺牲者（1213，DeadlockLoserDataAccessException），从测试的 race 辅助方法直接抛出。该测试、OrderServiceImpl 与 SeckillOrderMapper 均不在 PR #5 改动内，同一代码在 bbb167e 上两次 CI 通过；牺牲者事务整体回滚，不产生多余订单；生产消费者的 ConsumerFailureClassifier 把 TransientDataAccessException（含死锁）归为可重试，测试直接调服务方法没有这层重试。
- 验证：`gh run rerun 38065842025 --failed` 后第 2 次两个 job 均成功。
- 未完成 / 下一步：这条并发测试会偶发死锁失败；可让 race 对死锁牺牲者按消费者语义有限重试，或改走真实消费者入口，未改。

### 2026-10-11 00:24 · 本地 Claude Code · main
- 改动：OrderCommitBoundaryIT 的 race 辅助方法把派发抽成 deliver，遇到 TransientDataAccessException（死锁牺牲者、锁等待超时）最多重试 5 次，对应生产消费者把这类异常归为可重试、broker 重投；断言不变，生产代码未改（feature/it-deadlock-retry 上的 b63b119，fast-forward 合并到 main）。obsidian-vault 面试案例稿单独提交并推送（3cd6807a，只含该稿，其余工作区改动未动）。云容器 /data/ms/mini-seckill 从 pro/multi-instance-coordination（1fc9e77）切回 main 并拉到 b63b119，工作树干净。
- 验证：本机 JDK 17 `mvn -B -q test-compile` 通过；CI run 38067275383（b63b119，feature 分支 push）两个 job 成功，Failsafe 57 项（OrderCommitBoundaryIT 20）0 失败 / 0 错误 / 0 跳过。死锁是偶发的，一次绿色 CI 不能证明重试路径被走到过。
- 未完成 / 下一步：面试案例稿定稿写进 Agent后端面试笔记蒸馏 的上传正文（用户自己写）；多主机、网络分区、Redis/MySQL 故障转移、协调元数据丢失与驱逐、新旧版本混跑和性能影响仍未测。

### 2026-10-11 01:55 · 本地 Claude Code · feature/coordination-perf-ab
- 改动：压测编排支持 PR #5 前后 A/B（b4b64ba，只改 benchmark 脚本、离线测试和 CONSUMER-POOL-BUDGET 文档）：`BUDGET_BASE_SHA` 指定基线，`BUDGET_MATRIX=ab-shared` 只交错 baseline 与新 jar 共享池；观测和报告把 CANCELLED 墓碑（11）算作终态、不算订单。随后在云容器跑 e2a6f96（PR #5 前的 main）对 b4b64ba 的 3 轮交错 A/B（00:38–01:32，单 JVM、100k profile、共享 40 连接），瘦身后的证据入库为 `benchmark/evidence/devcloud-linux-x64/coordination-perf-ab-b4b64ba3/`。新版本 app 日志里逐条 `COORD_SEND_RESULT` 共约 240 万行，入库时滤掉，只保留计数和原文件哈希。结果写进 devcloud 证据说明末节、README 与 MULTI-INSTANCE-* 文档，原来指向“末节”的链接改为按节名引用。
- 验证：CI run 38068274155（b4b64ba）两个 job 成功；本机 `python -m unittest` 跑 pool-budget 33 项、coordination 36 项，全部 OK，其中 11 项需要 Linux/POSIX 而跳过。归档 SHA-256 `0fa79beb…b0119` 与 sidecar 一致，包内运行数据逐个校验一致。计划 42 档，执行 35 档，对账 35/35 通过，磁盘全部 low-stall。入口 60 秒稳定档：baseline 三轮 5000、4500、4000/s，新版本三轮都是 3500/s；新版本 4000/s 三轮丢弃 4.68%–6.87%。3000/s 下单接口中位 3.4–3.6 → 8.3–8.7 ms，HTTP p99 36–52 → 112–169 ms。同档 Redis CPU 约为旧版 2.5 倍。3500/s 负载期落库 3216–3317 → 2183–2389/s。新版本认领重发 0 次，baseline 646–857 次。
- 未完成 / 下一步：PR #5 的性能代价已实测，但来源没有拆开。可以做“只换预扣脚本”（只校验扫描到的分桶或改用 MGET）和 `COORD_SEND_RESULT` 降级的消融，降日志级别时要同步改复测场景 07 的日志断言。多实例下的性能没测。本分支合并后容器要切回 main。

### 2026-10-11 10:26 · 本地 Claude Code · perf/coord-reserve-mget
- 改动：先在容器上起一个临时 redis-server（127.0.0.1:16390，不持久化，跑完关闭），对四种预扣脚本各执行 5000 次 × 3 轮，取 `INFO commandstats` 里 EVALSHA 的单次耗时。库存充足时：现版本 151–159 µs，MGET 48–50 µs，只校验扫到的分桶 20–21 µs，PR #5 前的旧脚本 8.7–9.3 µs。只剩 bucket 127 有货、平均扫 64 个空桶时：现版本 148–150、MGET 49–50、只扫 85–86、旧脚本 30–31 µs。脚本与结果入库为 `benchmark/evidence/devcloud-linux-x64/lua-reserve-bench-20261011/`。据此 `coord_reserve.lua` 改为一次 MGET 读全部分桶再逐个校验，只对返回空值的 key 补查 TYPE，失败语义与逐个 TYPE+GET 相同。没有采用“只校验扫到的分桶”：快售罄时它更慢，而且会放过未扫到的损坏分桶。`SeckillProducer` 的 `COORD_SEND_RESULT` 由 INFO 降为 DEBUG，原生复测脚本给三个 JVM 打开这一日志（场景 07 要读它）。测试替身增加 MGET；新增单测：选中的分桶正常时，后面的分桶缺失或非数字，仍要拒绝且不写任何数据。
- 验证：本机 JDK 17 `mvn -B -q clean test`：35 个测试类、243 项，0 失败 / 0 错误 / 0 跳过；生产脚本去掉注释后与微基准里的 MGET 版逐行相同；`python -m unittest`（UTF-8 模式）跑 coordination 与 pool-budget 两组离线测试共 69 项 OK，其中 11 项需要 POSIX 而跳过。未带 UTF-8 模式时，pool-budget 有 1 项因 Windows 默认 GBK 读 REPORT.md 报错，属于本机环境问题。集成测试和 CI 待推送后看。
- 未完成 / 下一步：推送后看 CI；容器上先跑九场景正确性复测，通过后再与 e2a6f96 交错做性能 A/B（阶梯和上一轮相同）；结果出来后更新文档、面试稿和简历条目，再合并。

### 2026-10-11 11:45 · 本地 Claude Code · feature/coord-reserve-mget
- 改动：上一条的提交 670a096 原在 perf/coord-reserve-mget，CI 只对 main 和 feature/** 触发，所以分支改名为 feature/coord-reserve-mget，提交不变。在容器上依次跑了九场景复测和性能 A/B，证据入库为 `coordination-670a0968/`（299 个文件）和 `coordination-perf-ab-670a0968/`（648 个文件，3.6 MB）。A/B 的 `pre-suite-app.log.gz` 是上一轮最后一组留下的约 25 MB 日志，按同样方式滤掉逐条确认日志。结果写进 devcloud 证据说明最后一节；MULTI-INSTANCE-COORDINATION、-VALIDATION、-RETEST 与 README 的性能描述更新为优化前后两组数。
- 验证：CI run 38105309579 两个 job 成功（Surefire 243、Failsafe 57、Python 8/33/36）。九场景复测（10:28–10:34）9/9：容器内 243 单元通过；01 注入偏差 76 ms 后写回 APPLIED；07 的迟到确认仍记录为 `changed=0`。性能 A/B（10:35–11:41）计划 42 档、执行 42 档，对账 42/42 通过，磁盘全部 low-stall。入口 60 秒稳定档：baseline 5000、5000、4500/s，优化后三轮都是 4500/s（优化前三轮都是 3500/s）。3000/s 下单中位 3.3–3.6 对 4.4–4.9 ms，HTTP p99 41–51 对 49–61 ms（优化前 112–169）。3500/s 负载期落库 3342–3386 对 2969–3120/s（优化前 2183–2389）。Redis CPU 约为 baseline 的 1.8–1.9 倍（优化前约 2.5 倍）。两个压缩包的 SHA-256 都与 sidecar 一致，包内运行数据逐个核对一致。
- 未完成 / 下一步：MGET 与日志降级各自的贡献没有拆开；1000/s 时优化后有两轮 p99 偏高（38、72 ms），原因未查；清算脚本、HGET 和多出的往返仍是剩余代价。合并 main 后容器切回 main。

### 2026-10-11 12:16 · 本地 Claude Code · feature/bench-warmup
- 改动：更正上一条“1000/s p99 偏高原因未查”。查了上一轮的 metrics.jsonl：每组新起 JVM 后，第一档开压时连接池只有 minimum-idle 8 条；72 ms 那一轮开压后约 1.5 s 内池里只有 13 条连接、245 个请求在等，两轮偏高时 p95 都正常，所以是压测方法的冷启动问题。93dd4e6 给 `cap-step.sh` 加了不计时的预热（WARMUP 秒数，默认 0；WARMUP_RATE 默认 1000/s）：先重置、加载，证明排空后第一档才重置并开始计时，预热不写窗口、报告行，也不参与停止判断。`pool-budget-suite` 默认传 `BUDGET_WARMUP_SECONDS=15` 并写进 manifest，CONSUMER-POOL-BUDGET 文档同步补充。新增离线测试，覆盖两点：预热未排空时第一档不重置；预热不进 steps.tsv。随后在容器上做了只跑 1000/s 的 3 轮交错验证，证据入库为 `warmup-check-93dd4e6d/`（209 个文件），README 末尾新增一节写分析和验证。
- 验证：新增的 ScriptTests 在容器 Linux（Python 3.6）上跑通；CI run 38110240892 两个 job 成功（Surefire 243，Python 8/34/36，新测试在 CI 执行且通过）。验证运行（12:01–12:14）6 档对账全过，磁盘全部 low-stall；第一档开始时池里已有 40 条空闲连接；1000/s HTTP p99 baseline 11.7–12.6 ms、优化后 11.8–12.7 ms，下单中位 2.5 对 2.8–2.9 ms。压缩包 SHA-256 与 sidecar 一致，包内运行数据核对一致。
- 未完成 / 下一步：两版在窗口中段都偶有约一秒连接池全满（baseline 3 次、优化后 4 次），没有拉高 p99，来源没查。拿第一档与 2026-10-11 之前的证据比较时，要注意那些运行没有预热。合并 main 后容器切回 main。

### 2026-10-12 11:55 · 本地 Claude Code · feature/coord-accept-fused
- 改动：换到新开的独占云服务器（CVM，root）继续压测。机器：TencentOS Server 4.4（内核 6.6），AMD EPYC 9754，KVM 16 核 32 线程（原容器是 32 核配额），61 GB 内存、无 swap，`/data` 是独立 50 GB 云盘 `vdb`（原容器是共享盘 `vdc`），有 root 和 Docker。按原容器同版本原生安装：JDK 17.0.20.1、Maven 3.9.9、k6 v2.3.0、Redis 7.2.7（源码）、RabbitMQ 3.13.7 + Erlang 26.2.5.19（源码编译：el9 RPM 需要 OPENSSL_3.4.0 符号，系统 OpenSSL 是 3.0）；MySQL 用系统源的 8.0.45（原容器 8.0.46），启动参数不变。`setup.sh` 相应修改并补上原先手工做的 mysqld 去能力位副本（b374ef2）。11:54 启动新机器基线 A/B：`BUDGET_BASE_SHA=e2a6f96… BUDGET_MATRIX=ab-shared BUDGET_RATES="1000 2000 2500 3000 3500 4000 4500 5000" DISK_DEVICE=vdb`，候选 b374ef2（`src/main` 与 main 761981b 相同），3 轮交错、每组预热 15 秒，阶梯比之前多一档 2500；证据目录 `/data/ms/results/pool-budget-20261012-115446-b374ef27-50010`。
- 验证：冒烟（main 构建的 jar，预热 5 秒后 500/s 跑 10 秒）5001 单全部落库，丢弃 0、系统错误 0，对账通过，HTTP p99 20.6 ms。A/B 进行中，未出结果。
- 未完成 / 下一步：A/B 跑完后核对、瘦身入库，并与原容器结论对照（物理核数不同，绝对数字不可横比）。同时做下一项优化：接纳时的在途清算和排队状态投影合并成一个脚本，每单 Redis 往返从 5 次降到 3 次，再做正确性复测和 main 对优化版的 A/B。

### 2026-10-12 12:01 · 本地 Claude Code · feature/coord-accept-fused
- 改动：cb567fb 把下单接纳时的三次 Redis 调用合成一个脚本。原来本地消息 INSERT 成功后，Java 先 HGET 读在途登记，再执行带全部 128 个分桶 key 的 `coord_settle.lua`（不退款，分桶其实不读不写），再执行 `coord_project.lua` 投影“排队中”；新的 `coord_accept.lua` 只传 5 个 key（版本、在途、期限、用户锁、状态），在一个脚本里校验 owner 前缀、版本布局和分桶序号范围，删在途登记、换版本，再按 `coord_project` 的规则投影“排队中”（只有当前 owner 才写、不覆盖终态；清算被拒绝时照样投影，和原来两次独立调用一致）。下单每单 Redis 往返从 5 次（SET NX、预扣、HGET、清算、投影）降到 3 次（SET NX、预扣、接纳），与 PR #5 之前相同。`resolveUncertain` 仍用 HGET + `coord_settle`：它可能退款、需要分桶 key，且不在热路径上。新增 5 个 Lua 用例（无退款清算并投影、不覆盖终态与他人锁、拒绝时登记保留但仍投影、已清算时只投影、写前类型检查）、1 个协调器用例（不再有 Java 侧 HGET）和 1 个真实 Redis 集成用例。
- 验证：本机 Windows/JDK 17 `mvn -B -q clean test`：35 个测试类 249 项，0 失败 / 0 错误 / 0 跳过。CI run 38201724058 两个 job 成功：Surefire 249，Failsafe 58（StockCoordinationIT 15），0 失败 / 0 错误 / 0 跳过；Python 8 / 34 / 36 OK。新机器上的正确性复测和性能 A/B 还没跑。
- 未完成 / 下一步：新机器基线 A/B 跑完后，先跑九场景正确性复测，再做 main 对 cb567fb 的 3 轮交错 A/B。

### 2026-10-12 12:07 · 本地 Claude Code · feature/coord-accept-fused
- 改动：11:55 那轮新机器基线 A/B 在 12:03 手动停掉（SIGTERM，worker 正常收尾并打包，DONE.json 为 failed / interrupted，原始包留在新机器 `/data/ms/results/`）。原因是新机器的数据盘限住了落库：`/data` 是 50 GB 高性能云盘，三档窗口内每秒写 IO 中位数 3281–3574、利用率 95.4–96.6%、写等待约 0.5 ms，1000/s 那一档就已打满（按腾讯云高性能云盘公式，50 GB 的上限是 1800 + 30×50 = 3300 IOPS）。MySQL 每次提交都刷 redo 和 binlog，RabbitMQ 持久化消息和 Redis RDB 也写这块盘。baseline 第 1 组：1000/s 窗口内平均每秒落库 821 单；2000/s 入口不丢但窗口内只落库 30994 单（平均 1058/s），HTTP p99 182 ms；2500/s 丢弃 12.5%、系统错误率 6%。在这种情况下比较 Redis 往返的优化会被磁盘掩盖，所以停掉 MySQL、Redis、RabbitMQ，把 `/data/ms/data` 换成 40 GB tmpfs 重新初始化。MySQL 参数不变（binlog 开、sync_binlog=1、innodb_flush_log_at_trx_commit=1、buffer pool 128 MB），只多了 binlog 10 分钟过期和 256 MB 切分（每单约 3 KB binlog，整套 A/B 约 20 GB，内存盘放不下）；为此 `services.sh` 支持可选的 `MYSQL_EXTRA_ARGS`，默认不设、原容器行为不变（37a8858）。
- 验证：tmpfs 上冒烟（main 构建的 jar、预热 15 秒）3000/s 跑 20 秒：60001 单、丢弃 0、系统错误 0，HTTP p99 47 ms，负载期平均每秒落库约 2400 单，进程 CPU 合计约 25 核（java 922%、mysqld 1081%、k6 252%、RabbitMQ 160%、Redis 81%），回到 CPU 受限。12:06 在 37a8858 上启动九场景正确性复测（业务代码即 cb567fb），结果待出。
- 未完成 / 下一步：九场景通过后，连续跑两组 3 轮交错 A/B：main 761981b 对优化版、e2a6f96 对优化版。新机器的数字只在本机比较；数据在内存盘上，磁盘分组不再反映中间件的写入。

### 2026-10-12 12:15 · 本地 Claude Code · feature/coord-accept-fused
- 改动：无代码改动。12:14 在新机器上连续启动两组性能 A/B（一个后台脚本串行跑，后一组等前一组打包完再启动）：先 `BUDGET_BASE_SHA=761981b…`（main），再 `BUDGET_BASE_SHA=e2a6f96…`（PR #5 之前）；候选都是 a1e8d92（业务代码即 cb567fb），`BUDGET_MATRIX=ab-shared`，`BUDGET_RATES="1000 2000 3000 3500 4000 4500 5000 5500"`，3 轮交错、每组预热 15 秒，`DISK_DEVICE=vdb`。两组的 JVM 都加了 GC 日志（`-Xlog:gc:file=/data/ms/logs/gc-%p.log:time,uptime`，堆仍是 2 GB），用来对照之前“窗口中段约 1 秒连接池全满”的时刻。第一组证据目录 `/data/ms/results/pool-budget-20261012-121440-a1e8d926-82607`。
- 验证：九场景正确性复测（coordination-20261012-120647-37a88589，12:07–12:14）9/9 通过，matrix_completed=true，新机器容器内 `mvn -B clean verify` 249 单元通过；终检：01 SUCCESS 14883、剩余 15117 与 Redis 一致；04、05 各有 1 条取消墓碑且只退款一次；08 SUCCESS 15000；09 稀缺库存 41 件全部卖完；九组在途登记与期限集合都清空、重复订单 0、队列清空。逐场景细节入库时再核对。
- 未完成 / 下一步：两组 A/B 预计 14:30 前后跑完；之后统一下载、校验、瘦身入库，写新机器证据说明，再更新协调文档、README 和面试稿。

### 2026-10-12 13:01 · 本地 Claude Code · feature/coord-accept-fused
- 改动：无代码改动。第一组 A/B（main 761981b 对优化版 a1e8d92，业务代码即 cb567fb；pool-budget-20261012-121440-a1e8d926-82607，12:14–12:57，中途未干预）跑完，原始包下载到本机。第二组（e2a6f96 对优化版）12:57 自动启动。
- 验证：原始包 SHA-256 `1a90adf2…7fc8ce` 与 sidecar 一致。计划 48 档、执行 27 档（其余为过拐点不跑），27 档对账全过（TIMEOUT/DEAD/取消墓碑 0、重复 0），40 连接预算校验全过；中间件数据在内存盘上，磁盘分组全部 low-stall，不说明问题。入口 60 秒稳定档：main 三轮都是 3000/s（3500/s 三轮丢弃 1.89%–4.39%），优化版三轮都是 3500/s（4000/s 三轮丢弃 6.50%–8.58%）。同档（各 3 轮）：下单接口中位 1000/s 1.7–1.8 → 1.5 ms，2000/s 2.4 → 1.8 ms，3000/s 9.1–9.5 → 4.7 ms；3000/s HTTP p99 110–135 → 75–120 ms；3500/s main 下单中位 459–525 ms、p99 812–1604 ms，优化版 56–68 ms、213–426 ms。Redis CPU 均值 41–42 → 36–37%（1000/s）、73 → 65–66%（2000/s）、81–82 → 75–76%（3000/s）；Java CPU 300–303 → 284–286%、624–632 → 593–596%、942–946 → 884–892%；MySQL CPU 两版相同。负载期落库（60 秒 SUCCESS/s）两版相同：3000/s 约 2910–2940，3500/s 约 2090–2200（新机器 MySQL 先到顶）。12:17 采环境时调过一次 `rabbitmqctl status`（约 9 秒 CPU），落在 main 第 1 轮 2000/s 那一档，该档 p99 24 ms，与另两轮 21–28 ms 无差别。
- 未完成 / 下一步：等第二组跑完；之后用 tc 只给 Redis 端口加 0.25 ms 单向延迟，做一组 main 对优化版的短 A/B，看 Redis 不在本机时少两次往返的影响；GC 日志对照“连接池约 1 秒全满”；全部完成后瘦身入库、写证据说明。

### 2026-10-12 13:56 · 本地 Claude Code · feature/coord-accept-fused
- 改动：无代码改动。第二组 A/B（e2a6f96 对优化版 a1e8d92；pool-budget-20261012-125740-a1e8d926-139898，12:57–13:46，中途未干预）跑完。随后做了一个 binlog 探针（`binlog-probe-20261012-134945`）：优化版 jar、2000/s 恒定 150 秒，窗口开始后第 20、50、80、110 秒各执行一次 `FLUSH BINARY LOGS`，10 Hz 采 Hikari 排队数和 binlog 文件切换时刻。13:55 起用 tc 只给 Redis 端口双向各加 0.25 ms 延迟（`redis-cli --latency` 平均 0.60 ms，MySQL 不受影响），跑 main 对优化版 1000/2000/3000/s 交错 3 轮（pool-budget-20261012-135530-a1e8d926-220817），进行中。
- 验证：第二组原始包 SHA-256 `7551ec7b…18a4`（机器上 sidecar），计划 48 档、执行 30 档，30 档对账全过、40 预算全过、取消墓碑 0。入口稳定档两版三轮都是 3500/s（e2a6f96 3500/s 丢弃 0–0.14%，优化版 0–0.48%；4000/s 两版都失败，丢弃 8.39%–10.78%）。同档：下单接口中位 1000/s 1.2 对 1.5 ms、2000/s 1.4 对 1.8 ms、3000/s 2.4–2.5 对 4.6–4.8 ms；Redis CPU 23 对 36–37%、42 对 65–66%、45–46 对 75%、3500/s 40 对 76–77%（约 1.6–1.9 倍）；Java CPU 多 3%–5%；3500/s 负载期落库 2391–2537 对 2149–2233/s。与第一组合看（两组的优化版结果一致）：本机上 PR #5 的协议让稳定档从 3500 降到 3000，接纳合并后回到 3500，与改造前持平；剩余代价主要在 Redis（预扣仍要 MGET 128 个分桶，利用率高时排队）。binlog 探针：4 次切换前后 2 秒内 Hikari 排队都是 0，窗口内只有 1 秒排队数为 1（当时只有 11 条连接在用）。第一组 1000/2000/s 窗口里的“池子全满”都是某个采样点 40 条连接同时被占用、排队 7–139、下一秒恢复，与 GC 停顿、每分钟的对账任务都对不上，binlog 切换也已排除，根因未查到。
- 未完成 / 下一步：等 Redis 延迟这组跑完后关掉 tc；下载、校验、瘦身入库；写证据说明，更新 README、协调文档和面试稿素材。可以继续做的优化：减少预扣时要校验的分桶数（单主 Redis 下分桶没有并行收益），或换成一个 hash 存分桶。

### 2026-10-12 14:41 · 本地 Claude Code · feature/coord-accept-fused
- 改动：Redis 延迟这组 A/B（pool-budget-20261012-135530-a1e8d926-220817，13:55–14:37）跑完后关掉 tc（Redis 往返回到 0.06 ms）。第二组、延迟组和 binlog 探针都已下载、校验、瘦身入库到 `benchmark/evidence/cvm-linux-x64/`（accept-ab-vs-e2a6f96-a1e8d926、accept-ab-redis-rtt-a1e8d926、binlog-probe-20261012-134945，连同 `ab-chain.sh`、`binlog-probe.sh`、`redis-netem.sh`）；证据说明补齐第二组、延迟组、连接池全满排查和本目录结论；README、MULTI-INSTANCE-COORDINATION（第 6 节性能段与第 7 节表格新增一行）和 MULTI-INSTANCE-VALIDATION 同步。
- 验证：延迟组原始包 SHA-256 `9f68a69e…703ebc`、第二组 `7551ec7b…8618a4`，都与 sidecar 一致，包内运行数据逐个一致（只有 `sources/` 因 Windows 路径限制未解出）；入库文件不含主机名、内网域名和 IP。延迟组计划 18 档、执行 18 档，全部通过、对账全过、取消墓碑 0；netem 队列发送 3654 万个包、丢弃 0。Redis 往返 0.6 ms 下 main 对接纳合并：下单接口中位 1000/s 4.8 → 3.3 ms、2000/s 6.6 → 4.2–4.3 ms、3000/s 18.7–19.6 → 10.4–10.7 ms；HTTP p99 16–18 → 12、24–29 → 17–22、152–156 → 105–119 ms；3000/s 负载期落库 2136–2184 → 2406–2441/s，队列峰值约 4.0 万 → 2.6–2.7 万。与不加延迟时相比，main 下单中位多 3.0–3.1 ms、接纳合并多 1.8 ms，比例约 5:3，与下单接口 5 次和 3 次 Redis 往返一致。`scripts/check-evidence.sh` 通过（37 个 summary、131 个必需文件）。
- 未完成 / 下一步：推送后看 CI，通过后 fast-forward 合并到 main，新机器上的仓库切回 main。可以继续的优化：减少预扣时校验的分桶数；连接池偶发全满的根因（需要同时抓 MySQL 内部等待）；多实例下的性能。新机器上的中间件数据在 tmpfs 里，重启会丢，下次用前要按 `services.sh` 重新初始化。

### 2026-10-12 14:49 · 本地 Claude Code · main
- 改动：feature/coord-accept-fused（b374ef2…177569b：新机器 setup/services 脚本、接纳合并 cb567fb、新机器全部证据与文档）fast-forward 合并到 main 并推送；新机器上的仓库切回 main（177569b，工作树干净）。新机器的中间件仍在运行，数据在 40 GB tmpfs `/data/ms/data` 上，重启后要重新初始化；云盘上的旧数据目录保留在 `/data/ms/data.vdb-20261012`。
- 验证：分支最后一次 CI run 38213806501 两个 job 成功；main 推送触发的 CI run 38214115861（headSha 177569b）两个 job 成功。
- 未完成 / 下一步：同上一条（预扣分桶数、连接池偶发全满根因、多实例性能）。面试稿素材：这一轮可写的点是“换机器后瓶颈变成云盘 IOPS 及判断方法”“接纳合并把每单 Redis 往返 5 → 3、稳定档 3000 → 3500/s”“tc 只给 Redis 加延迟后，往返次数按 5:3 进入下单延迟”“连接池全满排查排除了 GC、对账和 binlog，根因未定”。

### 2026-10-12 15:09 · 本地 Claude Code · feature/bucket-ablation
- 改动：开始做上一轮列出的三项。（1）分桶：74c70df 给 `pool-budget-suite` 加 `BUDGET_MATRIX=buckets`（同一个候选 jar，100k profile 默认 128 个 Redis 库存分桶对 `--seckill.stock-shard.enabled=false`，交错 3 轮），给三 JVM 正确性复测加 `COORD_REDIS_BUCKETS`（默认 64，0 为不分桶，此时终检只比 Redis 总量与 MySQL）；已核对初始化、预扣、清算、接纳、对账开始和写回 6 个脚本都支持不分桶（n=0）。（2）连接池偶发全满：新写 `stall-probe.sh`，2000/s 恒定 480 秒，10 Hz 同时采 Hikari 排队/在用、MySQL 状态（Threads_running、行锁等待、脏页、Innodb_buffer_pool_wait_free、检查点年龄与异步/同步刷脏阈值）、PSI 与 CPU steal、JVM GC 与安全点日志，并开 Tomcat 访问日志（毫秒起止时间）；14:57 在 main 的 jar 上跑了一次（stall-probe-20261012-145737）。
- 验证：CI run 38215477062 两个 job 成功（Surefire 249、Failsafe 58，Python 8 / 35 / 37）；本机离线测试同样通过（Windows 上 12 项 POSIX 用例跳过）。探针：窗口 483 秒，所有连接被占满且有排队的片段 3 次（+63.5 s 排队 7、+78.5 s 排队 73、+441 s 排队 5）。前两次时检查点年龄是 78.9 MB、81.0 MB，异步刷脏阈值 77.5 MB、同步阈值 83.1 MB（redo 容量为默认 100 MB）；整个窗口里检查点年龄每 20 秒都冲到约 83 MB，27% 的时间超过异步阈值的 90%。第三次时 42 个线程都在 MySQL 里执行，前后 2 秒内 Innodb_buffer_pool_wait_free 增加 1372（buffer pool 为默认 128 MB；280 秒后表变大，空闲页等待开始累积，440 秒那 20 秒共 1537 次）。PSI 和 steal 全程为 0；JVM 等安全点最长 36 ms、GC 停顿 10–32 ms；访问日志里没有某个 50 ms 内请求数成倍突增（最多 260 个，平均约 200），排除压测端突发。
- 未完成 / 下一步：15:08 起在线把 redo 容量调到 2 GB、buffer pool 调到 4 GB，其余不变，再跑同样的探针做对照；对照完恢复默认值。之后跑分桶对照 A/B，再做多实例性能。

### 2026-10-12 15:20 · 本地 Claude Code · feature/bucket-ablation
- 改动：连接池偶发全满的对照实验做完。15:08 在线把 `innodb_redo_log_capacity` 调到 2 GB、`innodb_buffer_pool_size` 调到 4 GB（其余不变），用同一个 jar 和同样的 480 秒 2000/s 探针再跑一次（stall-probe-20261012-150853），15:19 恢复默认值（100 MB / 128 MB，已核对生效）。另提交 b84e053：`BUDGET_MATRIX=multi`（同一个 jar，单 JVM 池 40 对 18080–18082 三个 JVM 池 14/13/13，worker id 7–9），`app.sh` 支持 `APP_INSTANCES` 与 `APP_POOL_SIZES` 且 stop 会停掉所有有 PID 文件的实例，`k6-order.js` 支持 `BASE_URLS` 按迭代轮询（每次迭代的 token 和下单发往同一实例，不经过负载均衡），metrics 采样器按 `MS_APP_URLS` 抓多个实例、计数求和、连接池按端口标注，冷重置遇到任一实例 PID 文件都拒绝；单实例行为不变。
- 验证：两次探针对比：检查点年龄峰值 83 MB（顶到同步刷脏阈值 83.1 MB）→ 545 MB（异步阈值 1591 MB 的 34%）；空闲页等待 1537 次以上 → 0；MySQL Threads_running ≥ 40 的采样 26/4712 → 1/4712；Hikari 排队采样 75 → 43 个、排队峰值 73 → 16；HTTP p95 7.6 → 5.0 ms，p99 40 → 45 ms（单次运行），下单中位 1.9 → 1.8 ms。调大后剩下的 2 次全满（排队 2、16）都和 MySQL 无关：一次在 JVM 进入 Cleanup 安全点前等了 80 ms（实际停顿 80.1 ms），一次在 30 ms 的 G1 停顿后。结论：压测里的偶发全满主要来自 MySQL 默认的 100 MB redo 容量和 128 MB buffer pool，写入压力下检查点年龄反复冲到同步刷脏阈值、表变大后再叠加空闲页等待，所有写连接一起卡住几十毫秒。标准压测配置仍保持 MySQL 默认值，便于和之前的证据对比。多实例代码本机离线测试 38 项通过（8 项 POSIX 用例跳过），CI 进行中。
- 未完成 / 下一步：CI 过后在新机器上跑分桶对照（main 的 jar，128 个分桶对不分桶，交错 3 轮），再跑多实例对照；不分桶若明显更好，再用 `COORD_REDIS_BUCKETS=0` 跑一次九场景正确性。

### 2026-10-12 16:10 · 本地 Claude Code · feature/bucket-ablation
- 改动：无代码改动。分桶对照（pool-budget-20261012-152138-25e45d34-305682，15:21–16:06，中途未干预）跑完：候选 25e45d3（业务代码同 main），`BUDGET_MATRIX=buckets`，100k profile 默认 128 个 Redis 分桶对 `--seckill.stock-shard.enabled=false`，交错 3 轮，MySQL 为默认配置。随后同一后台脚本自动开始多实例对照（pool-budget-20261012-160618-25e45d34-362707），之后还会跑不分桶的九场景。
- 验证：原始包 SHA-256 `04496783…c5ec110`（机器上 sidecar），计划 42 档、执行 28 档，28 档对账全过、40 预算全过、取消墓碑 0。稳定档：128 分桶三轮都是 3500/s；不分桶 3000、3500、3000/s（3500/s 丢弃 0.22%–2.75%，1/3 通过）。同档：Redis CPU 37 → 27%（1000/s）、65–66 → 49%（2000/s）、76 → 51–52%（3000/s）、78 → 47–49%（3500/s）；下单接口中位 1.5 → 1.3、1.8–1.9 → 1.5–1.6、4.8–5.2 → 3.4–3.5 ms，3500/s 39–46 → 154–342 ms；HTTP p99 3000/s 79–84 → 56–66 ms、3500/s 222–267 → 653–994 ms。3500/s 负载期落库 2152–2174 对 2110–2319/s，队列峰值 6.7–7.0 万对 5.1–6.8 万，Java、MySQL CPU 两组接近。结论：关闭分桶省下约三成 Redis CPU、3000/s 以下延迟更低，但入口稳定档没有提高；3500/s 时 MySQL 已是瓶颈，Redis 变快后消费者落库多、积压少，入口分到的连接少、延迟和丢弃增加（这是按现象推断的资源竞争，没有分开测入口与消费者各自的借连接时间）。因此不改默认配置，把“单主 Redis 下可关闭分桶”记为部署选项。
- 未完成 / 下一步：等多实例对照和不分桶九场景；全部完成后下载、瘦身入库，写证据说明并更新文档。

### 2026-10-12 16:12 · 本地 Claude Code · feature/bucket-ablation
- 改动：更正上一条对分桶对照 3500/s 的解释，并据此加了 9648275：`BUDGET_MATRIX=channels`（同一个 jar，Spring Boot 默认的发布端 channel 缓存 25 对 `--spring.rabbitmq.cache.channel.size=256`）。
- 验证：从分桶对照的 metrics.jsonl 按窗口算入口各阶段平均耗时（计数与累计耗时的差分）：3500/s 时插本地消息 2.66–2.80 对 2.73–2.81 ms、借连接 1.23–1.35 对 1.33–1.45 ms、回源查订单 1.81–1.93 对 1.78–1.90 ms，两组相同；首次发送到 RabbitMQ 20.8–21.2 对 37.5–41.1 ms（3000/s 时 0.92–1.38 对 1.61–2.13 ms）。所以上一条“消费者抢走入口的连接”的推断不成立，差别在发送这一段。16:11 查 RabbitMQ 管理接口：启动以来已新建 2695629 个、关闭 2695539 个 channel，当时（多实例对照的单 JVM 组 3000/s 前后）每秒新建 1171 个、关闭 1172 个，只有 1 个连接、38 个打开的 channel。发布端和消费者共用 Spring Boot 默认的 CachingConnectionFactory（一个连接、缓存 25 个 channel），3500/s 时按 21 ms 估算同时在发送的约 70 个，超出缓存的部分每次发送都要新开再关一个开启了 confirm 的 channel；延迟越高同时发送的越多，所以拐点很陡。关闭分桶后请求更快到达发送这一步，排队更长。本机离线测试 39 项通过（8 项 POSIX 跳过）。
- 未完成 / 下一步：多实例对照和不分桶九场景跑完后，跑 channel 缓存对照（2000–5000/s，交错 3 轮）；若明显改善，再考虑把默认配置改为 256。
