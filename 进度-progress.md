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
