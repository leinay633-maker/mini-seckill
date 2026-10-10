# 消费者连接饥饿修复与固定预算对照

基线：`29b794e9ac9ba3e8c73643c7de6b7b802a81df0c`。分支：`pro/consumer-pool-budget`，PR #3。本文件只记录实现、测试和预先确定的复测口径；没有新的内网容器性能结果。历史 `REPORT*.md` 和 `benchmark/evidence/devcloud-linux-x64/` 不改。

## 1. 修了什么，保证到哪里

基线证据在 `benchmark/evidence/devcloud-linux-x64/README.md` 末章：抢占在 try 外，借连接失败后 MANUAL 投递未结算；事务开始借不到连接又被当作永久失败。该轮确认 6 条最终 TIMEOUT、2 条因开事务失败 DEAD。这不是“MQ 自动保证不丢单”。

现在的正常路径是：

```text
监听器设置 consumer 路由（在事务代理之前）
  → ConsumerOrderTransactions.create 开事务
  → CAS 抢占 SENT/SENDING/REPLAYED → CONSUMING
  → 原 OrderService 的订单、库存、日志、CONSUMED 写入加入同一事务
  → 提交；原 Redis afterCommit 投影仍在提交后执行
  → 退出/清理路由
  → 只结算一次 ACK
```

开事务、抢占 SQL、业务处理以及 reconcile/failStock/markDead 错误处理再失败，都在外层保护内。连接获取、可恢复数据访问、事务/提交不确定性按基础设施重试处理：回滚连同抢占，不写一个还需要连接的 FAILED 状态，不消耗消息表的业务发送 retry_count；释放事务连接后默认等待 250ms，再显式 `basicNack(tag,false,true)`。重投由数据库业务唯一键及消息 CAS 幂等，不依赖修改数据库重试状态才能恢复。真正的非重试业务异常仍走有事务的 DEAD 分支。

ACK/NACK 不在业务异常处理的 try 内。结算失败只关闭物理通道并传播错误，不再尝试第二次结算、不把已提交订单改为失败。`ConsumerPoolContext` 是非继承 ThreadLocal，异常/嵌套退出都会恢复原值。

**有界保证：** 单实例、数据库事实/消息持久化已建立、故障在业务排队超时之前恢复的范围内，连接饥饿不会再走这两条“投递悬挂/误判 DEAD”路径。不是任意长度故障下的无条件不丢单：原业务排队超时默认 10 分钟仍生效；数据库、磁盘或 MQ 长期不可用、进程崩溃、Redis 预扣到本地消息落库的窗口、多实例恢复 fencing 都未在本轮解决。宽泛事务错误可能持续重试，需要看 `seckill_mq_total{result="consume_retry_requeued"}` 与日志，不把它误称成“所有毒消息都有界自动恢复”。

## 2. 固定 40 连接的隔离开关

启动参数（需要重启，不是运行期动态开关）：

| 参数 | 默认/本轮约束 | 用途 |
|---|---|---|
| `seckill.pool-budget.enabled` | `false` | 关闭保留原单池自动配置；在 100k profile 下为共享 40 |
| `seckill.pool-budget.total-connections` | `40` | 开启时校验 2–40，并与已配置 Hikari maximum-pool-size 相等 |
| `seckill.pool-budget.consumer-connections` | `28` | 开启时消费者独占 28，入口侧为 40−28=12 |
| `seckill.consumer-execution.retry-backoff` | `250ms` | 释放事务连接后再等待，允许 0–30s；不是最佳值结论 |

隔离启用时只有两个物理 Hikari 池；主 DataSource 是路由对象，不是第三个连接池。两物理池总上限 40，总 minimum-idle 仍是 8：12/28 分配为 2/6 idle；20/20 分配为 4/4 idle。JDBC URL、凭据、连接超时等沿用原配置。事务管理器和 MyBatis 绑定同一个路由 DataSource，已绑定事务内的连接不会中途切池。

只有消费者监听器作用域使用 `consumer`；HTTP、发布 confirm 回调、异步入口日志、恢复/重试任务走 `admission`。池名固定，可分组观察。**12/28 与 20/20 都是估算的实验参数，不是经测量的最优值。** 入口池变小可能提高入口拒绝/超时，消费者闲置时入口也不能借走保留额度；同一块磁盘和数据库仍是共享资源。因此必须同时看入口错误/丢弃、SUCCESS、积压和追平，而不是只看消费者速度或只看 HTTP p99。

## 3. 一条命令后台复测

前提是用户原来的内网 Linux `/data/ms` 原生安装仍在：`env.sh`、JDK/Maven/k6、MySQL/Redis/RabbitMQ、管理 API 以及现有 native `docker` 兼容脚本。这里只面向那套可重置的测试数据，**不能用于生产或有他人同时压测的环境**。脚本会停止当前测试 app、重置原 benchmark 库表、`seckill:*` Redis key 和两个测试队列。

本机先审 PR、核对 CI，把 PR 分支送到同一个容器；在该分支仓库根目录运行：

```bash
bash benchmark/native-linux/pool-budget-suite.sh start
```

无需额外加 `nohup`。脚本返回 worker PID 和证据路径，整套流程脱离会话运行；不要求交接助手中途轮询。也可只查看计划（不启动服务、不重置数据）：

```bash
bash benchmark/native-linux/pool-budget-suite.sh plan
```

默认输出 `/data/ms/results/pool-budget-<时间>-<SHA>-<PID>/`，以及同名 `.tar.gz`、`.tar.gz.sha256`。**压缩包及其 checksum sidecar 都存在才是打包完成；`DONE.json` 的 completed 只表示矩阵调度完成，不等于所有档通过。** 失败也尝试保留 `worker-error.txt`、DONE 和不完整证据包；打包失败另记 `PACK_FAILED.txt`。

默认按旧同机证据选数据盘 `vdc`，并在启动前确认 `/proc/diskstats` 有该设备、记录 `mount.txt`/`lsblk.txt`。它不是可迁移的自动设备识别结论；同一容器设备变动时先核对真实数据盘，再在同一命令前加 `DISK_DEVICE=<已核对设备>`。不存在的设备直接失败，不会把无磁盘数据报成低卡顿。

高级参数：`MS_ROOT`（默认 `/data/ms`）、`BUDGET_RUN_ID`（唯一新目录名）、`BUDGET_ROUNDS`（默认 3）、`BUDGET_RATES`（空格分隔、严格递增）、`DRAIN_MAX`（默认 600 秒）。改参数会写入 manifest，不能与默认口径混写结论；`DRAIN_MAX` 不改变应用的 10 分钟业务超时。

流程与保护：

- `flock` 防本脚本并发运行；检查工作树干净、候选是基线后代、没有正在运行的 k6。它不能阻止另一个不遵守锁的外部压测，复测期间须独占测试环境。
- 用 `git archive` 生成基线和候选的独立源码快照，分别 `mvn -B -DskipTests package`；记录完整 SHA 和 jar SHA-256。无需手工给 jar 改名，不切 main、不 checkout 工作树。构建跳过测试仅为避免重复运行，不能替代拉取前的 PR CI 验证。
- 本次候选中的同一套 benchmark/采样脚本作用于两个 jar；脚本在运行目录有固定快照，不覆盖 `/data/ms/bin`，不在中途受源码更新影响。
- 各组启动/停止都有超时，保存每组应用日志。旧 JVM 停不下来时保留 PID、拒绝启动第二实例，不悄悄违反“单实例”；PID 内容非法或已指向别的进程时拒绝发信号。
- 一档未证明排空时不在活消费者下直接 reset 下一档；先保留最终快照并结束该阶梯，停止 JVM，确认 unacked 消失，再复用 `reset-env.sh --cold-only` 在下一独立组启动前清理旧夹具；该入口再次检查 PID 和 HTTP health，跳过尚未启动应用的初始化请求。避免旧版悬挂投递混入下一配置。

## 4. 预定对照矩阵

共同设置：单实例，Hikari 总上限 40、总 minimum-idle 8、连接超时 2000ms；100k profile 的消费者 8–32 并发、prefetch 100；活动缓存 250ms、首次 INSERT SENDING 融合开启。仅新池开关/分配及基线 jar 不同。两个旧入口开关不再作为本轮变量。

| 阶段 | 配置/顺序 | 每次运行 |
|---|---|---|
| A/B 3 轮 | 旧 29b794e 共享池 A / 新版 split28 B；AB、BA、AB | 相同完整阶梯，保留原拐点停止规则 |
| 同版池消融 3 轮 | shared、split28、split20；逐轮轮转先后，每配置各一次排首位 | 使用同一候选 jar，不关闭安全修复 |
| 独立诊断 | baseline、shared、split28、split20，各在 3000 与 4000/s | 每档 60 秒，计划 15/25 秒抓栈并记录实际时刻 |

默认阶梯 `1000 2000 2200 2400 2600 2800 3000 4000 5000`，细分旧 2000–3000/s 区间。共 23 个独立组、143 个**计划**档位；达到原拐点后更高阶梯标 not-run，不伪造为已执行或通过。k6 沿用 `unique`、constant-arrival-rate、60 秒、库存 `2×rate×60`、预分配 VU `max(rate,2000)`、max-VU 30000 以及原用户基数公式。

诊断与主性能样本分开：jstack、MySQL 状态、top 有额外开销；保留 target/actual 时间，不把命名为 25 的文件冒称精确第 25 秒，亦不混进 A/B 吞吐比较。

## 5. 对账与指标口径的修正

旧 `cap-step.sh` 按客户端 order_queued 判断追平。客户端 5 秒超时后服务端仍可受理，旧条件可能提前结束。本轮在停流后同时要求数据库非终态=0、MQ ready=0/unacked=0、数据库总数连续稳定至少 5 秒。观察 SQL 或管理 API 出错时记 unknown，不当作 0；600 秒仍未证明排空就留失败快照。

`drain_observed_tail_s` 包含这 5 秒安静保护以及观察开销，**不能直接拿它和旧表 5–9 秒作提升比较**；原始 `*-drain.jsonl` 可重算首次满足条件的采样时刻。SUCCESS 速率按 k6 启动 `t0..t0+60s` 的数据库采样估算，采样覆盖不足 90% 留空；SQL 采样时间改为查询返回后记录，避免把迟到查询结果记在早先时刻。不是精确请求生命周期计时，也不是长期持续容量证明；只有同一套新采样口径内对照才可比较。

机器可读 `summary.json` 与 `REPORT.md` 分开报告：入口阈值通过、最终对账、实际 40-budget 指标验证、60s SUCCESS/s 估计、停流尾部、积压、逐池 pending/active/acquire/usage/timeout。对账要求消息全部 CONSUMED、无 TIMEOUT/DEAD、成功订单=消息数、无重复业务键/订单号、分段库存扣减相符，且成功订单不少于客户端已确认入队数。实际订单多于客户端入队不自动算重复，但必须保留差值与错误率。最终 verify 和 `*-message-states.txt` 留供人工复核。

`metrics-sampler.py` 原来去掉 pool 标签会覆盖双池样本；现在 `pools` 保留两物理池，旧 `app` 字段给出总连接计数/计数器之和以兼容旧分析，时长 max 取池间最大值。借用/持有均值从各池累计 sum/count 的差分算，不平均“两个池的均值”。旧无分池标签采样无法补出历史分池数据。

磁盘仍用原 `disk-sampler.py`，从所有组开始之前采集。复用旧文档的辅助阈值并**在本次运行前固定**：写 await >2ms 的采样秒数至少 5 秒标 noisy，覆盖不足 90% 标 unknown，其他标 low-stall；保留所有档及连续值，不自动排除 noisy/unknown，不把相关性单独当因果。诊断缺失、低覆盖、脚本退出等均保留，不将缺失补零。

## 6. 原值 → 新值 → 理由与证据等级

| 项目 | 原值 → 新值 | 依据 |
|---|---|---|
| 消费成功路径的独立连接/提交边界 | 抢占 + 订单事务 2 个 → 同一事务 1 个 | 代码路径计数；SQL 语句数未减半，不是性能倍数 |
| Hikari 上限 | 共享 40 → 仍总共 40；可拆 12/28 或 20/20 | 实现与配置测试；分配比例是估算实验参数 |
| minimum-idle 合计 | 8 → 8 | 固定预留规模，避免启动配置偷加连接 |
| 连接饥饿后的操作 | 无 ACK/NACK 或 DEAD → 回滚、250ms 退避、NACK/requeue | 单元与真实 Hikari/MySQL/RabbitMQ 有界故障测试 |
| 历史 3000/s 的负载期 SUCCESS | 2310–2556/s → **待同机复测** | 旧值来源为 evidence README 结果二；未填新值 |
| 历史持续完成范围 | 2000–3000/s → **待细分/复测** | 60s 阶梯只是短窗口观测，不能改称 soak 上限 |
| Java 单元/集成 | 146/32 → 185/42 | 断点核心提交 e29e452 的已下载 CI 报告，非性能数据 |
| 新编排与分析测试 | 无 → 30 个离线测试 | 本次编辑环境实跑；含合成负例、真 shell 和后台失败打包，不是云压测 |

## 7. 验证记录与交接

已核对核心提交 `e29e452b77eac88cff504a71a4cb9e87ebb22912` 的 GitHub Actions run `37934795603`：185 单元、42 集成，均 0 失败/错误/跳过；`ConsumerPoolBudgetIT` 10 项覆盖真实 Hikari 耗尽、真实 MySQL 抢占锁超时、抢占/错误处理回滚、池双向隔离、并发重复、ACK 故障以及真实 RabbitMQ MANUAL prefetch=1 重投后恢复。Redis/metrics 在这些测试中是替身，不称完整生产故障演练。

下载的 consistency-evidence artifact `11618195753` SHA-256：`1dc9cbe862fea18cef34337852437583a9f85e688d521ac5bf9689fca101b9a4`，与 GitHub digest 一致。报告中 32 份 Surefire XML 合计 185，5 份 Failsafe XML 合计 42。最终提交以后续最新 CI 为准。

本次编辑环境执行：

```bash
python3 -m unittest discover -s scripts -p 'test_pool_budget_benchmark.py' -v
python3 -m py_compile benchmark/native-linux/pool-budget-*.py benchmark/native-linux/metrics-sampler.py benchmark/native-linux/sampler.py
bash -n benchmark/native-linux/pool-budget-suite.sh
bash -n benchmark/native-linux/cap-step.sh
bash -n benchmark/native-linux/app.sh
```

30 项离线测试通过，0 失败/错误/跳过。编排脚本兼容原容器 Python 3.6 标准库；shell/后台测试在 Linux/POSIX 上执行，Windows 跳过这组 shell 用例。新 Python 测试加入现有 CI，Java/Maven/非跳过集成门禁不删除。编辑环境没有 Maven，也无法访问用户的内网容器；未在这里运行新 jar 性能测试，不保证第一次在目标机运行不会遇到环境差异。失败证据会留在运行目录，修环境前不得把失败数字隐藏。

本机完成同机复测后一次性汇总完整样本，核对最终事实与配置预算再合并。仅随后更新面试稿第十二节 Q54、Q55：实现、CI 故障回归和同机性能实测分开叙述。证据压缩包不含 `env.sh`，但日志/SQL 状态可能含环境信息；上传公开 evidence 之前仍需脱敏检查。
