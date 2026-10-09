# CI 验证快照：入口连接需求改造

记录时间：2026-10-09 17:47（北京时间）。这份记录是功能/一致性回归证据，**不是云研发容器的性能复测**。容量、延迟、CPU、持续落库及追平仍为“待实测”。

## 1. 代码、运行与检出身份

- PR：[#2](https://github.com/leinay633-maker/mini-seckill/pull/2)，分支 `pro/capacity-knee`，基准 `64955b80a9f13a4841a2cedb32ea648a5f34b292`。
- 功能代码提交：`38347fc27a5d8b670ec3ffb30ce3951766402ebc`，tree `ac2c5da1981694494cf74d87696e2916d0fcaca9`。
- GitHub Actions：[run 37912946477](https://github.com/leinay633-maker/mini-seckill/actions/runs/37912946477)，`test` 与 `integration-test` 两个 job 均 success。
- CI 默认检出 PR 临时合并提交 `bcd7598b79c651d081ea22c1733e5840927eb99f`。已核对其 tree 也是 `ac2c5da1981694494cf74d87696e2916d0fcaca9`，与功能提交字节树相同；**这不是将 PR 合并到 main**。
- manifest 实际环境：Linux x86_64、Temurin 17.0.20.1、Maven 3.10.0、Docker 28.0.4；工作区干净。集成 Maven 于 2026-10-09 09:44:19 UTC 完成，退出码 0。

后续记录文档的提交不改变上述功能代码；最终分支提交的检查状态以 PR 最新 CI 为准。本文件冻结这次已核对的运行，不把尚未发生的复测补进快照。

## 2. 实际执行结果

已下载并解析原始 Surefire/Failsafe XML，与 `summary.json`、`maven.log` 交叉核对，未只依据绿色图标推断计数。

| 执行项 | 测试数 / 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `mvn -B clean verify` 单元 | 146 / 146 | 0 | 0 | 0 |
| `mvn -B clean -Pintegration-test verify` 中同一批单元 | 146 / 146 | 0 | 0 | 0 |
| 上述 integration profile 的集成 | 32 / 32 | 0 | 0 | 0 |

单元报告 29 份，集成报告 4 份；非跳过集成门禁通过。两次单元是不同 job 重跑，**不相加成 292 个独立测试**。JaCoCo 原 45% 下限、Python 证据校验器回归、Compose 配置与已有 benchmark 证据检查均通过；阈值和原工作流未降低或更改。

新增单元 34 项（基准 112 → 146）：

| 测试类 | 本次实际通过数 | 证明范围 |
|---|---:|---|
| `AdmissionCapacityPropertiesTest` | 2 | 默认/消融绑定、TTL 与数量边界 |
| `ActivityAdmissionCacheTest` | 16 | 生命周期失效、自然起止、TTL/慢读/过期失败、冷加载交错、事务内隔离 |
| `AdmissionCapacityTest` | 12 | 四组 SQL 调用计数、Redis miss 保留 MySQL 兜底、恢复门禁、INSERT 失败/零行、计时异常隔离 |
| `SeckillInitialSendTest` | 4 | 首次融合、关开关还原、重试不省守卫、协议配置不漂移 |

集成基准 23 → 32：`InitialAdmissionIT` 9、`OrderCommitBoundaryIT` 20、`SeckillConcurrencyIT` 2、`SkuStockMapperIT` 1，全部 0 skipped。新增 9 项使用真实 MySQL、生产 DDL/mapper、Spring 订单事务代理和独立连接；Redis/MQ 是确定性替身。确认发布前持久化、提交后未发可重试、发送失败保持重试、先提交后迟到回调不改终态、历史 SUCCESS/FAILED 的 token 拒绝、本实例先取 token 后关活动再下单拒绝。

## 3. 原始证据与完整性

同一 Actions run 的 artifacts：

| Artifact | ID | 下载 ZIP 的 SHA-256（已与 GitHub digest 核对） |
|---|---|---|
| `unit-test-reports` | `11607865100` | `0e392facbb37da884702b26eda4c575399daf9e280606eccd4efcf3b310c16c5` |
| `consistency-evidence` | `11606813881` | `4128dbae7f237c1441c301abeaad6ec06a008173a189f6937a4010e059dea6dc` |

`consistency-evidence` 内的 `sha256.json` 共 287 个文件校验项，逐项复算全部匹配；`summary.json.status=PASS`，`problems=[]`。归档包含 manifest、Maven 原始日志、XML/TXT 报告及覆盖率产物。Artifacts 有保留期限，重要验收时应另行保存原始包；本快照没有改历史报告哈希。

## 4. 当前编辑环境与尚未验证事项

当前编辑环境 `mvn -B clean verify` 无法启动：`mvn: command not found`。本地只验证了 `bash -n benchmark/native-linux/ab-rounds.sh` 及报告/字节完整性；以上 Java 结果明确来自 CI，不写成“本机已跑”。

未执行 k6、用户 32 核原生中间件容器的 A/B、真实 broker/Redis 故障或实杀进程。`InitialAdmissionIT` 的“提交后未发”通过确定性不调用发送模拟，不等于实杀证据。共享池是否仍饥饿、入口提升是否转化为持续完成能力、250ms 是否最优，都须按 [CAPACITY-RETEST.md](CAPACITY-RETEST.md) 实测。分析与不变量/保留缺口见 [CAPACITY-KNEE.md](CAPACITY-KNEE.md)。
