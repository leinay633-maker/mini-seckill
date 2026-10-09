# 一致性回归与本机实验方案

本文件区分自动化正确性回归、真实容器故障演练和性能复测。测试参数是输入配置，不是运行结果；所有尚未执行的新性能/故障实验结果均为 **待本机实测**。实际执行前，先由使用本机的人确认是否运行演练，不能自动停止正在使用的容器或清理业务数据。

## 1. 最小可复现证据包

在仓库根目录使用 JDK 17、Maven、Python 3.10+。只跑不需要 Docker 的校验：

```bash
python -m unittest discover -s scripts -p 'test_consistency_evidence.py' -v
mvn -B clean verify
```

允许启动 Testcontainers 的环境中，一次执行并归档：

```bash
# Windows 用 python；Linux/macOS 也可以使用 python3。
# 不要求启动 docker-compose 应用栈，测试使用自己的临时数据库/Redis 容器。
python scripts/run-consistency-evidence.py
```

等价底层 Maven 命令：`mvn -B clean -Pintegration-test verify`。CI 额外保留原有 `./scripts/assert-integration-tests-ran.sh`，要求所有 `*IT.java` 都有非零、无跳过、无失败的 XML 报告。

输出默认进入已被 Git 忽略的 `benchmark/results/consistency-<UTC时间>/`，也可使用 `--output <新目录>`。脚本拒绝覆盖已有证据或把输出放进会被 Maven clean 删除的 `target/`。构建失败同样保留证据并返回非零，不因为找到了少量旧 XML 就宣称成功。

| 文件 | 用途 |
|---|---|
| `manifest.json` | 实际命令、退出码、开始结束时间、Git HEAD/工作区状态、JDK/Maven/Docker 版本、两份历史报告的 SHA-256 |
| `maven.log` | Maven 完整原始输出，包括失败堆栈；不是手工整理出来的“全绿”日志 |
| `surefire-reports/`、`failsafe-reports/` | JUnit XML 与文本报告；统计依据 |
| `summary.json` | 从 XML 汇总的测试/失败/错误/跳过数，以及未执行性能/故障实验的占位状态 |
| `site/jacoco/` | 本次构建产生的覆盖率报告；不修改项目原有 45% 门槛 |
| `sha256.json` | 证据文件摘要；用于文件完整性核对，不等于可信时间戳或防伪签名 |

脚本不自动将新结果写回历史报告，也不自动上传 Git。CI 的 `consistency-evidence` artifact 有保留期限；重要结果应在本机下载并留存原始文件、运行链接与 SHA，再决定是否另建新的版本化实验报告。

## 2. 已编码的确定性交错与并发断言

核心测试：`src/test/java/com/example/miniseckill/integration/OrderCommitBoundaryIT.java`。

它使用真实 MySQL 8/InnoDB、`sql/init.sql` 生产 DDL、实际 MyBatis mapper、Spring 事务代理；Redis 与指标是故障注入替身，没有连接 RabbitMQ。下面的故障注入不能代替第 4 节的真实容器演练。

| 检查 | 输入/交错 | 必须成立的事实 |
|---|---|---|
| 提交顺序 | 执行下单；Redis 回调内另开独立 JDBC 连接查订单与消息 | 回调开始时独立连接已能看到 SUCCESS 订单和 CONSUMED 消息 |
| 回滚 | 外层事务调用 service 后标记 rollback-only | 无订单、库存未减、消息仍 CONSUMING、无 SUCCESS 投影和新成功计数 |
| Redis 投影失败 | afterCommit 中的 Redis 写入抛异常 | service 正常结束，订单/扣库存/消息已提交，记录缓存失败指标 |
| 持久化拒绝 | 库存 0；成功事务先回滚，再调用失败记录事务 | FAILED 订单与 DEAD 消息一起提交；普通重试扫描不再返回该消息 |
| 拒绝终态 CAS 失败 | 消息已经 TIMEOUT，再尝试记录失败 | 插入失败订单也回滚，不覆盖 TIMEOUT、不写缓存 |
| 失败订单重复 | 先有 FAILED 订单，再以同业务键另一 request_id 重投 | 不变 SUCCESS，不扣库存；对应消息收敛为 DEAD |
| 非业务键冲突 | 制造其他用户已有相同 order_id | 当前用户无成功事实，不允许把消息标 CONSUMED |
| 旧重试快照 | 扫描 SENDING → consumer 提交 → 使用扫描出的旧记录执行失败/耗尽更新 | 迟到更新影响 0 行，已提交事实不变 |
| 状态集合矩阵 | CONSUMED / TIMEOUT / DEAD / CONSUMING，分别尝试发送侧更新 | 受保护；同时验证真正可重试状态仍能增加重试预算 |
| 同业务键并发 | 12 个不同 request_id、同用户/SKU，4 个测试线程，库存 3 | 1 个业务订单、只扣 1 件、12 条消息按成功事实收敛；不把多条消息当多次购买 |
| 不同用户争抢 | 12 个用户、4 个测试线程、库存 3 | 3 成功、9 持久化失败、余量 0、已售 3；订单与对应消息终态一致 |

以上数量都是固定测试夹具的输入与断言。**不能用 12 除以测试执行耗时计算 QPS，更不能外推生产峰值。** 测试等待超时仅用于防止测试挂死，不是恢复 SLA。

补充单测覆盖 ACK IOException/runtime 异常、失败持久化不可 ACK、未知订单状态、CAS 未命中不发补偿、Redis 失败不阻断恢复批次，以及校验器缺报告/跳过/空报告/失败的负例。

## 3. 验收 SQL 与计数口径

停止新流量、记录在途任务和配置，在 MySQL 同一个只读一致性快照中取证。下面是诊断查询示例，不会修改订单；返回 0 行异常才满足对应断言。MySQL 客户端退出码为 0 只表示 SQL 执行成功，不表示业务不变量成立。

```sql
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

-- 不应有业务键重复；DDL 的唯一约束也是这条不变量的兜底。
SELECT activity_id, user_id, sku_id, COUNT(*) AS n
FROM seckill_order
GROUP BY activity_id, user_id, sku_id HAVING COUNT(*) > 1;

-- CONSUMED 是消息状态 2；SUCCESS 恰好也是订单状态 2，来自各自 enum。
-- 必须关联事实，不能仅比较全表行数。
SELECT m.request_id, m.status AS message_status, o.status AS order_status
FROM seckill_message m
LEFT JOIN seckill_order o
  ON o.activity_id=m.activity_id AND o.user_id=m.user_id AND o.sku_id=m.sku_id
WHERE m.status=2 AND (o.id IS NULL OR o.status<>2);

-- 持久化的业务拒绝不能被普通技术失败重试循环重新消费。
SELECT m.request_id, m.status AS message_status, o.status AS order_status
FROM seckill_message m JOIN seckill_order o
  ON o.activity_id=m.activity_id AND o.user_id=m.user_id AND o.sku_id=m.sku_id
WHERE o.status=3 AND m.status IN (0,3,4,5,9);

-- 单行库存模式。分段开启时先对 sku_stock_segment 的实际扣减事实求和，
-- 不把可能尚未对账的 sku_stock 汇总行当成即时扣减事实。
SELECT * FROM sku_stock
WHERE available_stock<0 OR sold_count<0 OR available_stock+sold_count<>total_stock;
SELECT * FROM sku_stock_segment
WHERE available_stock<0 OR sold_count<0 OR available_stock+sold_count<>total_stock;

SELECT status, COUNT(*) FROM seckill_message GROUP BY status;
SELECT status, COUNT(*) FROM seckill_order GROUP BY status;
COMMIT;
```

还须按活动/SKU 将事实库存的已售数与 SUCCESS 订单数核对；分段模式看分段合计，非分段看主表。输入中允许同一业务键出现多条 request_id 时，**不能要求 `COUNT(CONSUMED) = COUNT(SUCCESS)`**。FAILED/DEAD 的分类也不能仅看死信队列长度。

上述服务端表查询无法证明 Redis 预扣后、写消息前退出的请求没有丢失，因为它可能没有服务器持久记录。要调查该窗口，必须另存客户端 request/token/业务键账本并关联 Redis reservation；目前该协议未实现，不能把 SQL 无异常当成已证明端到端无丢失。

## 4. 本机真实故障演练（全部待本机实测）

只在隔离环境操作。既有脚本会 reset 活动 1 / SKU 1001、操作本项目固定容器名；先确认没有其他应用消费者、压测任务或未归档结果。不要对正在使用的数据执行 `down -v`、清库或覆盖 `benchmark/evidence/mac-arm64/`。

### 4.1 新版本的已有演练复测

按 `REPORT-RELIABILITY.md` 第 8 节完成拓扑与停止其他消费者的顺序，再使用既有入口，保存到新的结果目录/标签：

```bash
# 各命令的 reset / 停服务行为必须先经本机用户确认。
BASE_URL=http://localhost:80 ./benchmark/run-suite.sh interview-grade-multi
BASE_URL=http://localhost:80 ./benchmark/run-fault-drill.sh
# backlog / soak 前，必须先停止 app-1..app-4 和 nginx，保留依赖组件。
./benchmark/run-backlog-drill.sh
./benchmark/run-soak.sh
./scripts/check-evidence.sh
```

复测前后记录 Git SHA、JDK/镜像版本、所有有效配置、库存模式、workerId、资源限制、k6 参数；记录请求和最终订单的分类计数、队列 ready/unacked、终态分布、恢复时间线与 SQL 取证。队列清空不能单独证明成功；失败订单不能从分母消失。

**结果：待本机实测。** 旧报告的 Redis 停止 180 秒、1000 条积压 9 秒追平、45 分钟长跑仍是 2026-07 的历史结果，不复制为本轮结果。

### 4.2 精确切点：数据库提交后、ACK 前关闭连接

目的不是随机 kill 后“看起来恢复了”，而是确认 ACK 丢失不会触发已提交订单的失败补偿。

在独立应用的开发调试会话中，将断点设在 `SeckillConsumer.consume` 最后的 `basicAck` 前，暂停该消费者线程；从另一连接确认该业务订单 SUCCESS、库存已减、消息 CONSUMED，再停止 RabbitMQ 或关闭该消费者连接。恢复线程，保存 ACK 失败日志，然后恢复 RabbitMQ/消费者并观察重投与查询。必须保留切点前 SQL 与连接关闭的时间线；没有证据证明命中切点就记“未命中”，不能记“通过”。

验收：订单/库存不反转；不会因为 ACK 失败新增业务 FAILED 订单或 MQ_CONSUME_DEAD 补偿；重投不重复扣减；最终查询仍返回原订单。RabbitMQ 连接恢复/重投耗时、重投次数、异常数：**待本机实测**。本轮单测只验证应用的异常处理边界，不验证 broker 的真实重投时序。

### 4.3 精确切点：提交后 Redis 状态投影失败

在独立开发调试会话中于 `publishStatusAfterCommit` 的 Redis 写入前暂停；先从独立连接确认订单已提交，再执行 `docker compose stop redis`，恢复线程。记录数据库/订单查询、`status_cache_write_failed` 和 MQ settlement；之后按既有恢复流程恢复 Redis，不直接绕过 fail-closed。

验收：MySQL 订单、库存和消息不回滚；无新增错误业务失败；已存在订单的查询能从 MySQL 返回实际状态。不能要求缓存更新必达，也不能将 afterCommit 当作异步队列。

真实 Redis 超时造成的 ACK 延迟、恢复耗时、缓存收敛表现：**待本机实测**。本方案没有在生产代码加入远程故障注入开关。

## 5. 性能复测的对照方法（待本机实测）

本轮未作性能优化承诺。要判断提交后投影及新增 SQL 分支的代价，应冻结本轮 SHA 和 `68858ab`，分别在同一台机器、同一依赖数据与镜像、同一配置下构建运行；两版本不能同时竞争同一套容器。

使用 `run-suite.sh` 原有 unique / duplicate / selltail 参数，各轮保存完整 k6 summary 和 verify；每种版本至少三轮，所有轮次留存。轮次数是实验计划，不是已完成次数。交错运行两个版本以降低环境漂移，冷/热启动与预热策略保持一致。固定夹具、重试/恢复配置、JVM、日志开关、库存分段和消费者并发都要入档。

分别报告入队 HTTP 延迟、系统错误率、成功/失败最终订单数、队列积压和追平时间。最终订单完成延迟需另采 request_id/业务键到终态的时间线，注明轮询间隔；不能把入队延迟改名为“下单落库延迟”，也不能把不同请求类型混在一起计算一个漂亮的 p95。现有脚本未自动产出完整 request 级端到端延迟分布，这部分仍需补采。

| 实验 | 基准实测 | 新版本实测 | 差值/比例 | 判定 |
|---|---|---|---|---|
| 同机三场景复测 | 待本机实测 | 待本机实测 | 待本机实测 | 未执行 |
| commit 后 ACK 失败 | 待本机实测 | 待本机实测 | 不适用吞吐对比 | 未执行 |
| commit 后 Redis 不可用 | 待本机实测 | 待本机实测 | 待本机实测 | 未执行 |
| 新版本 multi / backlog / soak | 历史报告另存，不直接充当本轮基准 | 待本机实测 | 待本机实测 | 未执行 |

完成后新建注明环境、SHA、命令、输入与原始文件路径的报告；查不到出处的推算要标“估算”并写公式和分母。本轮没有提供这类估算结果。
