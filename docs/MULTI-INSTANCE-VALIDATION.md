# 多实例协调：本轮验证身份与未运行项

验证日期：2026-10-10。代码基于 `e2a6f96fc6b55ac9dc97fd03dc3800e6d758960f`；恢复的远端断点为 `8ca5ec0eddf2a8a8ac21c9d6110e53e988835c1a`（仅增加 CI 源码/离线构建包）。最终本地提交及远端是否同步，以交付 manifest 和 PR 实际 head 为准。**旧断点 CI 通过不代表本轮业务改动 CI 通过。**

## 实际执行

编辑环境为 Linux、OpenJDK **21.0.12.1**，Java 编译 `release=17`；不是用户的 JDK 17 云容器。依赖通过断点 CI artifact 的离线 Maven 缓存取得，未安装新版本依赖。

```bash
/mnt/data/build-tools/maven/bin/mvn -o -B \
  -Dmaven.repo.local=/mnt/data/build-tools/repository clean verify
python3 -m unittest discover -s scripts -p 'test_coordination_suite.py' -v
python3 -m unittest discover -s scripts -p 'test_consistency_evidence.py' -v
bash -n benchmark/native-linux/coordination-suite.sh
python3 -m py_compile benchmark/native-linux/coordination-suite.py scripts/test_coordination_suite.py
bash scripts/check-evidence.sh
git diff --check
```

| 验证 | 实际结果 | 不能替代什么 |
|---|---|---|
| 最终 clean verify，结束 2026-10-10T11:57:56Z | 35 份 Surefire XML，242 单元，0失败/0错误/0跳过，BUILD SUCCESS | 不等于 JDK 17 CI 或 Testcontainers IT |
| JaCoCo | 原有45%行覆盖门禁通过（pom 规则为 LINE COVEREDRATIO ≥ 0.45，行 1800/1076 约 62.6%）；指令7631 covered /4141 missed，分支466/282 | 覆盖率不证明并发协议正确 |
| 新协调编排 unittest | 34项，0失败/0错误/0跳过 | 信号用例作用于短命Python子进程，不是三JVM订单实验 |
| 原 consistency evidence checker | 8项，0失败/0错误/0跳过 | 合成XML负例不是业务故障实测 |
| Python语法、Python3.6语法门禁、bash -n | 通过；Python3.6语法检查包含在新34项中 | 不是在Python3.6解释器运行完整native矩阵 |
| 历史证据门禁 | 131个必需文件、37个summary JSON通过 | 历史单机/多实例结果不自动迁移到本次改版 |
| git diff --check | 通过 | 不等于远端推送成功 |

本地构建 jar SHA-256：`5de5a0b6993511c822a4dbba84b38061875daf042127bfa897e7ca574bf144eb`。云机必须从候选完整 SHA 自行构建并记录自己的 jar 摘要，不要求不同JDK构建产物逐字一致。

## 原有编排测试在本环境的失败

另执行未修改的 `test_pool_budget_benchmark.py`：运行30项，出现2个error（同一测试的两个subtest）：

```text
test_ladder_never_resets_next_rate_after_unproven_drain (drain_rc=0)
test_ladder_never_resets_next_rate_after_unproven_drain (drain_rc=20)
subprocess.TimeoutExpired: ... cap-step.sh synthetic 1000 2000 ... timed out after 10 seconds
```

在**精确8ca5ec0基准**的独立 worktree 再执行，同样30项、同样两个10s超时error；这条对照本身已实际运行。未修改旧门禁、删用例或把超时补成通过。此环境失败的更深层原因未确定，不能断言所有平台都存在；此前断点CI曾通过，也不能据此跳过新提交的CI。结果是“新套件通过，旧套件存在已在基准复现的环境超时”，不是“所有Python测试通过”。

## 尚未执行

`StockCoordinationIT` 新增14个展开用例（12个普通@Test、1个布尔参数化测试展开2次），与现有集成源文件一起成功编译，但本环境没有Docker/MySQL/Redis，**没有执行** `-Pintegration-test verify`。原有43项集成基线不是本轮通过数字。

新IT检查实际MySQL错误1205证明唯一键被未提交事务阻塞；不再采用PR #4中曾在CI一直读到0的 `information_schema.innodb_trx` LOCK WAIT观测。它验证超时期间不退款、原事务实际提交/回滚后的两种清算结果；源码可执行不等于已测通过。

JDK17远端CI、Testcontainers真实MySQL/Redis、RabbitMQ真实故障联动、native三JVM九场景、Linux云机吞吐/延迟/恢复时长全部待执行。CI保留JaCoCo、Compose、历史证据、IT必须真正运行且无跳过的门禁，并加入新的离线编排测试。

## 原始输出摘要（随交付包 logs/ 与 test-reports/ 保存）

| 文件 | SHA-256 |
|---|---|
| coord-delivery-verify.log | `7e6390bb534ce8fbcc317c16424b9846d5a1faeb879557d14e0d80213bc6ec06` |
| coord-python-delivery.log | `f902bf1a8458dd93d972c1f963a2a0c5d10492cba6f7f62dba591aafff929306` |
| consistency-python-delivery.log | `31af1dd73070c76e44268a84661512812671a9614f79bcd153f0295370dbc59c` |
| pool-python-isolated.log | `fecb83298e825e7c389006dab820bb14196b39e182e0f224ffe2695cf837d14b` |
| pool-python-baseline.log | `ae9f32fd6154c188b83ac917d8817bb405d9eb5479ae64b8167416c3f63ae2b3` |
| coord-check-evidence.log | `e774a77c39085fb2e4c2909890ec3d9b00b598ff3eb49878fe4f70c30a59fb0f` |

这些是编辑环境的运行记录，不写入历史 evidence 目录，不把未来云机结果填进历史 REPORT。源文件和摘要在本次提交中可核对；网络写入状态须另外核实。

## 导入后的实际执行（2026-10-10，本机 Claude Code）

上文“尚未执行”描述的是交付时的状态，以下各项随后实际运行。

| 提交 | 验证 | 结果 |
|---|---|---|
| `2288ced`（交付包 bundle 快进导入并推送） | CI run 38052130288 | test 成功；integration-test 失败 2 项，均在本 PR 未改的 `OrderCommitBoundaryIT`（:237、:267）：断言仍假定刚以 SENDING 插入的行立即可被重试扫描到，而新 `insertPending` 会写入首发线程 20 s 发送租约。`StockCoordinationIT` 14 项在这次已通过 |
| `f86c1d5`（只改上述两条测试：先断言租约有效时扫描为空，再让租约过期继续原场景） | CI run 38052834059 | 两个 job 成功：Surefire 35 份 242 单元、Failsafe 57 项，0 失败 / 0 错误 / 0 跳过；Python 8 / 30 / 34 项 OK（交付环境的两个 cap-step 超时在 CI 未出现） |
| `f86c1d5` | 云容器 `coordination-suite.sh start` | 5/9：01/08/09 在 k6 正常结束后因 `KeyError: 'values'`（脚本按 handleSummary 布局读 `--summary-export`）没有执行最终校验；02 等新订单 10 s 超时（消息被投给暂停的 A，broker 尚未重投） |
| `1fc9e77`（只改编排与离线测试：两种 k6 布局都接受；02 的 A 租约 60 s、02/03 成单等待 45 s） | CI run 38053832422 | 两个 job 成功：242 单元、57 集成，0 失败 / 0 错误 / 0 跳过；Python 8 / 30 / 36 项 OK |
| `1fc9e77` | 云容器 `coordination-suite.sh start`（20:59–21:06） | **9/9 通过**，`matrix_completed=true`，归档与 sidecar 齐全；容器内 `mvn -B clean verify` 242 单元通过，jar SHA-256 `3da1a000f43411176f3691d3853cecb4bb05a25d79faa26b191abe66752b3ad5` |

两轮证据（含失败的一轮）、逐场景结果和入库处理见 `benchmark/evidence/devcloud-linux-x64/README.md`“多实例库存协调三 JVM 正确性复测”一节。仍未覆盖：多主机、网络分区、Redis/MySQL 故障转移、协调元数据丢失与驱逐、新旧版本混跑，以及多实例下的性能。单 JVM 前后性能 A/B（2026-10-11，入口稳定档 4000–5000 → 3500/s）见同一说明末节。
