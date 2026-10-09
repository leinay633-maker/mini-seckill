# 2026-10-09 CI 验证快照

这是一份代码回归记录，不是压测报告或本机 Docker 故障演练结果。

## 可追溯身份

- PR：[mini-seckill #1](https://github.com/leinay633-maker/mini-seckill/pull/1)
- 本轮首个已验证代码提交：`c8668c7ec6d6aa3f14501f5c2438c570ac6761df`
- 基准 main：`68858ab569ae5f133f02fb585ceb35b9fd336a4e`
- Actions PR 测试 checkout 的临时 merge SHA：`76d15d9c62cc9d14edbe1996a11e3184813f45ed`。这是 GitHub 的测试 ref，不代表已合并 main。
- [CI run 37898578944](https://github.com/leinay633-maker/mini-seckill/actions/runs/37898578944)，两个 job 均 success。
- 执行环境：GitHub-hosted Ubuntu 24.04.5，Temurin JDK 17.0.20+1。不是用户的 Mac M1，也不是本轮编辑容器。

## 实际结果

| 命令/门禁 | 通过 | 失败/错误 | 跳过 | 证据 |
|---|---:|---:|---:|---|
| `python3 -m unittest discover -s scripts -p 'test_consistency_evidence.py' -v` | 8 | 0 | 0 | test job 原始输出；是校验器的合成 XML 负例测试 |
| `mvn -B clean verify` | 112 项 Java 单元测试 | 0 / 0 | 0 | test job Surefire 汇总、BUILD SUCCESS |
| `mvn -B clean -Pintegration-test verify`（由证据脚本执行） | 112 项单元 + 23 项集成 | 0 / 0 | 0 | consistency-evidence 的 summary.json 与 XML |
| `assert-integration-tests-ran.sh` | 3 个源类 / 3 份报告 / 23 项测试 | 0 | 0 | integration job 输出 `sources=3 reports=3 tests=23` |
| JaCoCo | 通过现有 45% 门槛 | 0 | 不适用 | `All coverage checks have been met`；不在此推算新的覆盖率百分比 |
| Compose 配置组合校验 | 6 组 | 0 | 0 | test job Validate Docker Compose files |
| `check-evidence.sh` | 131 份文件、37 份 summary JSON 检查通过 | 0 | 0 | 仅检查既有证据，不代表重跑历史实验 |

23 项集成测试的组成：`OrderCommitBoundaryIT` 20 项（含参数化展开），`SeckillConcurrencyIT` 2 项，`SkuStockMapperIT` 1 项。新类使用真实 MySQL、生产 DDL 和 Spring 事务代理；Redis/metrics 在该类中是故障注入替身，不能声称该类进行了真实 Redis 或 RabbitMQ 故障演练。

测试执行次数不能相加成“224 个独立单测”：两个 job 都运行了同一批 112 项单元测试。

## 原始证据

- [consistency-evidence artifact](https://github.com/leinay633-maker/mini-seckill/actions/runs/37898578944/artifacts/11601666604)：Maven 原始日志、JUnit XML、summary、manifest、JaCoCo 与文件摘要。
- [unit-test-reports artifact](https://github.com/leinay633-maker/mini-seckill/actions/runs/37898578944/artifacts/11600873288)。
- [jacoco-html-report artifact](https://github.com/leinay633-maker/mini-seckill/actions/runs/37898578944/artifacts/11600893197)。

artifact 可能到期；本机交接时应留存需要长期使用的原始证据。后续提交以其自己的 CI run 为准，本快照不会自动证明未来代码通过；本轮后续只有文档变更时，也应在 PR 核对最终 head 的检查状态。

## 当前编辑环境与未执行事项

当前编辑环境执行 `python3 -m unittest discover -s scripts -p 'test_consistency_evidence.py' -v`：8 通过 / 0 失败 / 0 跳过；`python3 -m py_compile scripts/*.py` 通过。该环境未安装 Maven，未运行本机 Docker、k6 或真实故障演练。

新版本性能、恢复时长、真实 broker ACK 丢失、真实 Redis 投影故障与长跑：**待本机实测**。`REPORT.md`、`REPORT-RELIABILITY.md` 的 2026-07 历史实测数字全部保留，不替换成 CI 数字，也不借历史结果为本轮性能背书。
