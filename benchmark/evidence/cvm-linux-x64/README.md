# 独占云服务器上的复测证据（2026-10-12，CVM）

本目录是 2026-10-12 在一台新开的独占云服务器（CVM）上的实测数据。它和 `benchmark/evidence/devcloud-linux-x64/`（共享宿主上的 32 核配额容器）不是同一环境，物理核数、CPU 代际和存储都不同，**绝对数字不能横向比较**；两边可以对照的只有“同一机器内 A/B 的方向和幅度”。

## 环境

| 项 | 实际值 |
|---|---|
| 机器 | KVM 虚拟机，TencentOS Server 4.4，内核 6.6.119；AMD EPYC 9754（Zen 4c），16 核 32 线程；61 GB 内存、无 swap |
| 存储 | 系统盘 100 GB、数据盘 `/data` 50 GB（`vdb`），都是高性能云盘。**中间件数据目录 `/data/ms/data` 从 12:04 起是 40 GB tmpfs**（原因见下一节），日志和结果仍在 `vdb` |
| 中间件 | 原生进程，版本与 devcloud 一致，只有 MySQL 小版本不同：MySQL 8.0.45（系统源；devcloud 是 8.0.46），启动参数同 `benchmark/native-linux/services.sh`：binlog 开、`sync_binlog=1`、`innodb_flush_log_at_trx_commit=1`、buffer pool 128 MB、`--max-connections=300`；tmpfs 上另加 `--binlog-expire-logs-seconds=600 --max-binlog-size=268435456`。Redis 7.2.7（源码编译，默认配置，`noeviction`）。RabbitMQ 3.13.7 + Erlang 26.2.5.19（源码编译） |
| 应用与压测 | JDK 17.0.20.1（Temurin），k6 v2.3.0；应用参数和阶梯规则同 `pool-budget-suite.py`，k6 与应用、中间件在同一台机器上 |
| 搭建 | `benchmark/native-linux/setup.sh`（b374ef2 起支持 TencentOS 4：OpenSSL 3.0 装不了 el9 的 Erlang RPM，改为源码编译同一版本）；环境采集原文见 `cvm-env-20261012.txt` |

## 云盘 IOPS 限住了落库（12:03 停掉的第一轮）

第一轮按原样（中间件数据在云盘 `vdb` 上）跑 e2a6f96 对 b374ef2 的 A/B，在第 1 组 baseline 的三档之后手动停掉。三档窗口内磁盘每秒写 IO 的中位数为 3281–3574，利用率 95.4–96.6%，写等待约 0.5 ms，1000/s 那一档就已经打满。按腾讯云高性能云盘的性能公式，50 GB 的随机 IOPS 上限是 1800 + 30 × 50 = 3300。

| 到达率 | 丢弃 | 系统错误率 | HTTP p99 ms | 窗口内落库（平均每秒） |
|---|---|---|---|---|
| 1000 | 0 | 0 | 16 | 821 |
| 2000 | 0 | 0 | 182 | 1058 |
| 2500 | 12.5% | 6.0% | 2452 | 1164 |

MySQL 每次提交都要刷 redo 和 binlog，RabbitMQ 的持久化消息、Redis 的 RDB 也写这块盘。用窗口内写 IOPS 除以落库速度粗算，每落一单约 2.9–4.4 次写 IO（并发越高，组提交摊得越多），所以落库被限在每秒约 1000 单，远低于应用和 CPU 的能力。这个环境下比较 Redis 侧的优化会被磁盘掩盖，因此把三个中间件的数据目录换成 tmpfs 后重来：MySQL 的刷盘参数不变，只是 fsync 落在内存里。之后各轮比较的是代码路径和 CPU，不代表真实云盘部署的落库能力。

这一轮的原始包保留在新机器 `/data/ms/results/pool-budget-20261012-115446-b374ef27-50010.tar.gz`，本目录只摘录上表。

# 接纳清算与排队投影合并后的正确性复测（coordination-37a88589，2026-10-12 12:07–12:14）

候选 `37a88589480e46c8dec8dc94c3bfd36731d1207c`：业务代码与 `cb567fb` 相同（下单接纳时的在途清算和“排队中”投影合并为 `coord_accept.lua`），另含本机的 setup/services 脚本改动。CI run 38201724058 两个 job 成功（Surefire 249、Failsafe 58）。命令 `bash benchmark/native-linux/coordination-suite.sh start`，中途未干预；中间件数据在 tmpfs 上。机器内 `mvn -B clean verify` 249 单元通过，jar SHA-256 `eef28e6fb4c6750dcaf5b785ab46dc9d88fa8f0b1aa489562fcb78fc541df1b3`。

结果 9/9 通过（`DONE.json`：`status=passed`、`matrix_completed=true`），九组停机后的终检全部满足：在途登记和期限集合清空，重复业务键与订单号为 0，队列清空，Redis 总量等于 MySQL 剩余。逐场景：

- 01：12:07:45.794 把 Redis 总量从 25834 改成 0，584 ms 后节点 B 写回 APPLIED（expected 25834）。这段时间内的请求得到售罄：15000 次请求 = 14883 排队 + 117 售罄，与 200 次/s 的到达率相符；最终 SUCCESS 14883、剩余 15117。三个节点共 268 次受保护对账：APPLIED 1、INFLIGHT 38、VERSION_CHANGED 141、UNCHANGED 57、BUSY 31。
- 02、03：快照后暂停的修复者恢复时分别得到 VERSION_CHANGED 和 LEASE_LOST，库存不被旧快照覆盖。
- 04：A 预扣后暂停，B 通过墓碑取消并退款；A 恢复后旧 INSERT 撞唯一键（A 日志 1 条 ERROR），A、B 都记录 `cancelled=true`，只退款一次，剩余 9。
- 05：A 预扣后被强杀，B 取消并退款，重启后的 A 正常。06：A 在 INSERT 提交后被强杀，C 判定 `cancelled=false`，不退款，消息补发后成单。
- 07：A 的迟到发送确认记录为 `changed=0`。08：节点 A 在开压后 15.6 s 被暂停、25.1 s 被强杀后重启，最终 SUCCESS 15000。09：41 件库存、410 次请求，41 单成交、369 次售罄。

原始压缩包 SHA-256 `bbd7d10b3c53c1b8b869b1602ee66c20d6904ea40c844117b5447be668fdbb97`。

# 接纳合并的性能：main 对优化版交错 3 轮（accept-ab-vs-main-a1e8d926，2026-10-12 12:14–12:57）

`REPORT.md`、`summary.json` 由 `pool-budget-report.py` 自动生成，48 个计划档逐档列出。

| 项 | 实际值 |
|---|---|
| 基线 baseline | `761981bd9a6857c6904f95c6b9b728e3c9160ae5`（当前 main，预扣已是一次 MGET）；jar SHA-256 `9f547fd5…1ab5502` |
| 候选 | `a1e8d92658eb764658778f97b93916c7a7522124`（`src/main` 与 `cb567fb` 相同）；jar SHA-256 `4cddc3f4…deb2dee` |
| 环境 | 本机，中间件数据在 tmpfs；单个 JVM（18080），100k profile（Redis 128 个库存分桶），共享 Hikari 40（minimum-idle 8、超时 2000 ms），消费者 8–32、prefetch 100，活动缓存 250 ms 与首次 SENDING 融合开启；JVM 另加 `-Xlog:gc:file=/data/ms/logs/gc-%p.log:time,uptime`（堆仍为 2 GB） |
| 命令 | `BUDGET_BASE_SHA=761981b… BUDGET_MATRIX=ab-shared BUDGET_RATES="1000 2000 3000 3500 4000 4500 5000 5500" DISK_DEVICE=vdb bash benchmark/native-linux/pool-budget-suite.sh start`，每组预热 15 秒，中途未干预 |
| 矩阵 | 交错 3 轮（AB、BA、AB），每档前重置；60 秒 unique 用户、每次迭代先取 token 再下单；停止条件为系统错误率或丢弃 > 1% |

执行情况：计划 48 档，执行 27 档（其余过拐点不跑）。27 档对账全部通过（消息全部 CONSUMED，TIMEOUT 0、DEAD 0、取消墓碑 0，业务键与订单号重复 0，库存守恒），40 连接预算校验全过。磁盘分组全部 low-stall，但中间件数据不在这块盘上，这一项在本机没有意义。12:17 采集环境时调用过一次 `rabbitmqctl status`（约 9 秒 CPU），落在 baseline 第 1 轮 2000/s 那一档，该档 HTTP p99 24 ms，与另两轮的 21、28 ms 没有差别。

**入口 60 秒稳定档**

| 版本 | 1000–3000 | 3500 | 4000 | 各轮最高通过档 |
|---|---|---|---|---|
| main | 各 3/3 | 0/3（丢弃 1.89%–4.39%） | 未跑 | 3000、3000、3000 |
| 接纳合并 | 各 3/3 | 3/3 | 0/3（丢弃 6.50%–8.58%） | 3500、3500、3500 |

**同档对比（每格为 3 轮的范围）**

| 到达率 | 版本 | 下单接口中位 ms | HTTP p99 ms | 60 秒 SUCCESS/s | Redis CPU 均值 % | Java CPU 均值 % | MySQL CPU 均值 % | 池单次占用 ms |
|---|---|---|---|---|---|---|---|---|
| 1000 | main | 1.7–1.8 | 10–15 | 993 | 41–42 | 300–303 | 508–510 | 0.4 |
| 1000 | 接纳合并 | 1.5 | 12–13 | 992 | 36–37 | 284–286 | 508–511 | 0.4 |
| 2000 | main | 2.4 | 21–28 | 1971–1974 | 73 | 624–632 | 854–859 | 0.5 |
| 2000 | 接纳合并 | 1.8 | 22–23 | 1971–1974 | 65–66 | 593–596 | 847–858 | 0.5 |
| 3000 | main | 9.1–9.5 | 110–135 | 2933–2943 | 81–82 | 942–946 | 1183–1188 | 1.3 |
| 3000 | 接纳合并 | 4.7 | 75–120 | 2909–2932 | 75–76 | 884–892 | 1183–1190 | 1.0–1.1 |
| 3500 | main（未通过） | 459–525 | 812–1604 | 2141–2173 | 83–85 | 950–962 | 1049–1056 | 1.6 |
| 3500 | 接纳合并 | 56–68 | 213–426 | 2086–2200 | 77 | 931–936 | 1052–1078 | 1.5 |

- 每单入口少两次 Redis 往返、少传一次 135 个 key 的脚本调用后，同档 Redis CPU 按中值降约 7%–12%（1000/s 12%、2000/s 10%、3000/s 7%），Java CPU 降 5%–6%；下单接口中位在 1000–2000/s 少 0.2–0.6 ms，在 3000/s 从 9.1–9.5 ms 降到 4.7 ms，3500/s 从排队失控变为通过。
- MySQL CPU 和负载期落库两版相同：3500/s 两版的 60 秒 SUCCESS/s 都是约 2100–2200，积压峰值 5.9–7.5 万条，停流后 16–17 秒追平。本机的端到端持续落库先受 MySQL 限制，这次改动提高的是入口能稳定接住的到达率，不是落库速度。
- HTTP p99 在 1000–2000/s 两版范围重叠。

原始压缩包 `pool-budget-20261012-121440-a1e8d926-82607.tar.gz` SHA-256 `1a90adf28bafaaf5c055b714c17b1f08a4a431a5004f317e5f17d956807fc8ce`，与 sidecar 一致；包内 SHA256SUMS.json 的 10433 项中运行数据逐个一致，`sources/` 下 388 个文件因 Windows 路径限制未解出、不入库。入库时省去 `jars/`、`sources/`、`worker.pid`，日志与 JSONL 用 gzip 压缩，`host.txt` 里的主机名替换为 `<container-host>`；新版本确认日志已是 DEBUG，没有需要滤掉的逐条日志。
