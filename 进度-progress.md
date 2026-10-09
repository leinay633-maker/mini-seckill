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
