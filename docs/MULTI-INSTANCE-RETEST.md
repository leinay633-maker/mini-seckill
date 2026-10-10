# 原生三 JVM 协调复测：一次启动，完整归档

**状态：2026-10-10 已在目标云研发容器执行。候选 `1fc9e77` 九个场景 9/9 通过；首轮 `f86c1d5` 因编排缺陷 5/9（k6 摘要字段层级、场景 02 等待过短），修正后重跑。** 结果与两轮证据见 [devcloud 证据说明](../benchmark/evidence/devcloud-linux-x64/README.md) 末节。业务协议、冷升级限制见 [设计](MULTI-INSTANCE-COORDINATION.md)。本轮验证正确性，不做 PR #3 容量提升比较。

## 一条命令

本机审阅 PR diff、通过 JDK 17 CI 后，将候选完整提交放到原云容器仓库的干净工作树，在仓库根目录执行：

```bash
bash benchmark/native-linux/coordination-suite.sh start
```

**只可在原 `/data/ms` 独占 benchmark 环境执行。会停止旧测试 app、为消息表补字段、清空 benchmark 数据表、删除 `seckill:*` key、清空两个测试队列；不能用于生产或共用环境。** 旧的暂停 JVM 也必须全部排除。脚本只管理自身创建的进程，不会为过预检而随意杀陌生 JVM。

启动器自行 nohup，立即返回 PID、证据目录和最终包路径。随后脚本自己执行完整矩阵并打包，不需要手工后台化或中途轮询，也不需要中途切分支。只读计划可用 `bash benchmark/native-linux/coordination-suite.sh plan`，不会停机或重置。

固定使用已有 `env.sh` 的 PATH；依赖 Python 3.6+、JDK 17、Maven、mysql / redis-cli / jstack / k6 / tar / flock，以及已启动的本机 MySQL、Redis、RabbitMQ management。无需 Docker、nginx、haproxy、socat、iptables、tc，也不自动安装新中间件或调用 GitHub API。源代码用 `git archive` 固化，快照内 `mvn -B clean verify` 构建新 jar，保留源码/构建日志/XML/摘要；不覆盖 `/data/ms/bin` 或切换用户工作树。

`MS_ROOT` 可替换 `/data/ms`；`COORD_RUN_ID` 可指定仅字母数字点下划线短横线的唯一目录名。数据库凭据环境变量 `COORD_DB_USER` / `COORD_DB_PASSWORD`（默认原夹具 miniseckill），RabbitMQ 的 `COORD_MQ_USER` / `COORD_MQ_PASSWORD`（默认原夹具 guest）仅通过环境传递；公开证据前仍必须检查日志脱敏。Redis 使用既有本机无认证单主夹具，不自动配置账号或 ACL。

## 实验配置（不是实测最优参数）

三个原生 JVM 分别监听 127.0.0.1:18081/18082/18083；k6 直接轮流访问 URL，无代理。不同 workerId=1/2/3，Hikari maximum **14+13+13=40**，minimum **3+3+2=8**；单实例内共享池，不把 PR #3 的拆池消融混进来。每 JVM Xms512m/Xmx1g、2 消费线程、prefetch=20；Redis 64 buckets、MySQL 32 segments，全部节点一致。

修复租约默认 20s；场景03只把 A 缩短为 2s，B/C仍20s，便于可控交错；场景02把 A 延长为 60s：新订单的消息可能投给已暂停 A 的消费者，要等 broker 按心跳关闭 A 的连接才重投（实测约 13.7s），等待成单期间 A 的租约必须仍有效。02/03 等待新订单成功的上限为 45s。fixture 在途回收期限5s、扫描500ms；生产默认60s、扫描1s。发送租约20s不变，发送扫描从默认15s加密为1s；活跃负载场景的对账从默认60s加密为1s。requested Rabbit heartbeat=5s 是故障夹具参数，不能据此声称服务端实际协商值或最优值。暂停窗口和等待上限都是**估算实验参数**，失败必须原样记录，不能删掉慢样本。

关闭 token、防刷、JWT、本地 sold-out cache；每次负载只有 order HTTP，而历史容量测试一次迭代有 token+order。活跃负载是200次迭代/s、75s，计划15000次，库存30000；这是低速正确性流量，不是测得吞吐。稀缺库存为41、需求410、32 VUs；小整数是夹具输入。

Redis `maxmemory-policy` 必须 noeviction，脚本不静默修改；记录 maxmemory 和主机内存。内存余量、磁盘可写、同版全节点及没有外部脚本修改库存是运行前提。

## 九个场景与明确断言

| 场景 | 注入/交错 | 必须得到的证据 |
|---|---|---|
| 01 三节点负载中修复 | 20s 后仅把 Redis 总量改为0，保留 bucket/版本/登记 | 实际 k6 请求开始时间窗口内出现 APPLIED；不是停流后才写回；最终全表守恒 |
| 02 有效租约但快照已过时 | A 在 SQL 快照后 STOP；B 完成一次实时下单；A 在租约仍有效时 CONT | VERSION_CHANGED；在途表再次为空也不能覆盖，剩余9 |
| 03 旧租约恢复 | A 快照后 STOP 等2s租约过期；B 拿新租约并停在快照切点；A CONT | A LEASE_LOST，A 不得删除 B 的锁；释放B后正确核对 |
| 04 暂停的预扣被接管 | A 在预扣后 STOP；B/C 通过数据库墓碑取消并退款；同用户在B下单；A CONT | CANCELLED 不被旧 INSERT 重开、旧清算不多退、不删新owner；最终一单 |
| 05 预扣后强杀 | A 预扣后 KILL；恢复者取消；重启A | 孤儿预扣被清算一次，后续正常接纳，最终守恒 |
| 06 提交后强杀 | A 已 INSERT，但未清登记/发送时 KILL | 恢复者发现已提交，绝不退款；租约过期补发，重启A后成功 |
| 07 发送代际 ABA | 关闭首次融合；A claim 后 STOP；B 到期接管并完成；A CONT | 旧 token 完成回调 changed=0；只形成一单，允许重复物理消息 |
| 08 负载中消费者丢失 | B/C 持续入口；A 15s STOP、25s KILL、35s重启 | 中间积压可观察；排空后所有实际持久化接纳都有成功订单 |
| 09 稀缺库存 | 三节点竞争41件、410个独立用户 | SUCCESS 恰好41、库存0、其他业务订单0；不能以 MySQL兜底后大量失败冒充全通过 |

故障点为测试 profile +显式 enabled+本地私有目录的三重开关，默认关闭，无远程 HTTP 故障接口。arm/hit/release 文件为一次性切点；脚本在 hit 后才发信号，不能用“差不多睡几秒”冒充命中事务边界。PID、`/proc` start ticks、jar路径和本次run marker都匹配才发信号，PID复用拒绝。每组最后先证明自身所有 JVM 已退出，再允许下一组冷重置；无法证明就停止剩余矩阵。

单机三个 JVM 和进程信号不等于多主机部署、网络分区或 Redis/MySQL 故障转移。新单测中的信号控制测试使用短命 Python 进程，不能当作上述业务实测。

## 验收与证据完整性

停流后等待 DB 非终态、Redis在途/期限表、Rabbit ready/unacked 全部为0，连续5个观测周期，再做受保护warmup。证明 JVM 全停后取得最终跨系统结果；负载中的 SQL、Redis、MQ 各自观测并不构成跨系统同一原子快照。

最终检查：总库存=available+sold；sold=SUCCESS订单；无业务键/订单号重复；所有非CANCELLED的本地消息均对应成功订单；没有其他订单、TIMEOUT/DEAD、未完成消息、孤儿CONSUMED、残留在途或MQ积压；Redis总量=64个bucket之和=MySQL剩余。该条“消息数等于成功数”只用于本套每用户一次的夹具，不替代项目通用的按业务键关联验收。客户端queued不得超过最终成功数；不能仅以queued为准，因为客户端超时后服务端仍可能已经持久化。

k6 非0退出、系统错误、已报告丢弃、负载不足、未执行故障点均不通过。零丢弃时某些已有k6输出不含 `dropped_iterations`：保留 null，同时要求完成迭代数满足计划并等于请求/业务分类总数；不悄悄补0。活跃修复还要求 APPLIED 时间确实在请求开始时间窗口内。缺失或异常观测记 unknown，重试观察保留异常日志，不能转换为0队列或通过。

没有信号权限、端口冲突、JVM停不下、构建失败、断言失败都写出失败和未运行格；独占锁同时兼容 `pool-budget-suite.lock`，但不能阻止不遵守锁的外部压测。

输出位于：

```text
/data/ms/results/coordination-<时间>-<SHA前缀>-<PID>/
  manifest.json / DONE.json / SUMMARY.md / SHA256SUMS
  candidate.jar / source/ / build-surefire-reports/ / build-site/
  maven-verify.log / host.txt / redis-policy.json / schema-migration.json
  01-.../ ... 09-.../
    events.jsonl / app日志 / launch身份 / fault切点 / SQL-Redis-MQ观测
    k6-summary.json / load-coverage.json / final.json / jstack / prometheus
```

**同名 `.tar.gz` 和 `.tar.gz.sha256` 均存在，才证明打包步骤完成。** `DONE.json` 的status仍须为passed且9/9；只有matrix_completed不能替代通过。失败也尽力归档；磁盘满等导致归档失败时写PACK_FAILED，不能谎称拿到完整包。一次性下载后先核外层摘要，再解包核 `SHA256SUMS`，检查全部矩阵而非只看生成表格。Jstack/Prometheus是尽力诊断，错误日志保留；不是性能归因的充分证据。

复测归档必须新增目录，不覆盖原 REPORT/evidence。核对结果、PR完整SHA与CI后再合并，最后才更新外部面试稿；本分支不改 obsidian-vault。
