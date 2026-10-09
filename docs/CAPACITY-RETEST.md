# 容量改造复测交接

本轮性能结果：**待实测**。基准 jar 是 `deb719a`，不是一致性加固之前的 `68858ab`。保持 [已测环境和工作负载](../benchmark/evidence/devcloud-linux-x64/README.md)；不修改历史 REPORT 或已有原始证据。

## 1. 本机验证与云端构建

在本机 `C:\GitHub\mini-seckill` 拉 `pro/capacity-knee`，先记录 `git rev-parse HEAD`、确认干净工作区，再运行 `mvn -B clean verify`。具备 Docker 的环境运行 `mvn -B clean -Pintegration-test verify` 和 `scripts/assert-integration-tests-ran.sh`；无 Docker 的本机必须注明集成未运行，不以 skipped 代替通过。

云研发容器沿用 `/data/ms` 布局，拉取同一提交，用 JDK 17 构建。记录 Java/Maven、完整 Git SHA、构建命令与 jar SHA-256；不能只凭文件名判断 jar 版本。保留 `/data/ms/jars/new-deb719a.jar`，新 jar 命名 `capacity-<短SHA>.jar`。从新分支同步 `benchmark/native-linux/ab-rounds.sh` 到 `/data/ms/bin/ab-rounds.sh`；其余原生服务、app、采样与阶梯脚本沿用已测版本。

## 2. 同机阶梯与交错

以下示例中的 `<短SHA>` 替换成实际构建提交；不是性能目标数字。复测仍先覆盖已测的 1000/2000/3000 档，只有按原停止条件通过后才继续更高档位。每档 60 秒，每次迭代 token+order，每档重置、库存两倍计划迭代数、相同 VU/错误/丢弃判定和追平流程。

```bash
cd /data/ms/bin
AB_RUN_ID="capacity-$(date +%Y%m%d-%H%M%S)" \
AB_VARIANTS='capacity-<短SHA> new-deb719a' \
./ab-rounds.sh 3 1000 2000 3000 4000 5000
```

`ab-rounds.sh` 仍逐轮新旧交错、每次重启应用并按原方式预备；某版本触发原停止条件就不继续该轮更高档。`AB_VARIANTS` 只决定两个 jar 标签，不改变 workload。未提供时仍是历史的 `new-deb719a base-68858ab`，**本轮必须显式设置**。AB_RUN_ID 避免与旧证据目录混写；目录已存在会在停应用之前拒绝，而非覆盖历史样本。每轮保存对应 jar.sha256。需要反转先后顺序时换一个新的 AB_RUN_ID 并反转两个标签，不能复用原目录。

不要把 4000/5000 写成预测容量。这些只是待跑阶梯上限；原停止条件、系统状态及已测结果决定是否继续。共享宿主噪声大，报告每轮值和版本内波动，不只挑最好一轮。真实对账失败时，即使吞吐好看也不能通过。

## 3. 四组消融：定位收益，不混入扩容

用 `app.sh start <新jar> [额外参数]` 与原 `cap-step.sh <独立label> <rates...>` 手动交错消融。每组仍重启、相同热身/重置及同样阶梯。基准 `deb719a` 不认识新开关，不给它添加伪造控制参数。

| 新 jar 组别 | 额外启动参数 | 热活动成功迭代入口 SQL（代码计数） |
|---|---|---|
| 都关闭 | `--seckill.capacity.activity-cache-ttl=0ms --seckill.capacity.initial-sending-enabled=false` | 5 |
| 仅活动缓存 | `--seckill.capacity.activity-cache-ttl=250ms --seckill.capacity.initial-sending-enabled=false` | 3 + 摊销回源 |
| 仅首次融合 | `--seckill.capacity.activity-cache-ttl=0ms --seckill.capacity.initial-sending-enabled=true` | 4 |
| 两项开启（默认） | `--seckill.capacity.activity-cache-ttl=250ms --seckill.capacity.initial-sending-enabled=true` | 2 + 摊销回源 |

新 jar 两项关闭仍含新诊断代码，不等于与 deb719a 字节相同；它用于结构消融，旧 jar 用于版本对照。40 连接、8–32 消费线程、MySQL 持久化配置必须保持不变。池隔离/扩大只能另起实验，不并入本轮收益。

## 4. 拐点处需采集什么

继续用已有 `diag-knee.sh` 在拐点抓 jstack / MySQL，保留原文，不只保留筛选结果。同步抓取 Prometheus 原始快照：Hikari 等待/持有、入口分阶段 timer、活动缓存、线程/连接、MQ ready/unacked；某项未暴露就记未采集。重点核对活动查库等待是否减少、token 回源是否成为占比更大的下一瓶颈、消息 INSERT 是否仍被消费事务/异步日志争抢连接阻塞。

每秒订单采样和负载结束后的追平记录不能省略。先看 SUCCESS 订单、库存守恒、业务键唯一性、消息与订单事实关联，再写 admission 结果。固定 unique 场景可以对比入队与成功订单数；在故障重投或多 request_id 对同一业务键时不要强行要求消息行数等于订单行数。

管理正确性另做小规模功能演练：活动关闭前先取 token，关闭响应后再下单应拒绝；另一实例/直接 SQL 关闭存在剩余 TTL 窗口。保留数据库旧 SUCCESS 和 FAILED 订单，仅删除对应 Redis 状态/幂等键后再取 token，仍应拒绝，不扣新资格。这是正确性验证，不加入性能采样段。

## 5. 填表和验收

原始数据放 `benchmark/evidence/devcloud-linux-x64/<新唯一运行目录>/`，追加 README 的新版本章节，不改已有表。记录所有轮次，包括失败、丢弃和未追平。更新 PR 中待实测，核对后才合并。

| 对照项 | 已知基准 deb719a | capacity 分支 |
|---|---|---|
| 60 秒入口稳定档 | 已测 2000 次迭代/s | 待实测 |
| 临界档 | 3000/s，三轮两轮丢弃、一轮通过 | 待实测 |
| token / order 尾延迟 | 见历史各轮 summary，不取单个最好值 | 待实测 |
| Hikari 等待线程 | 第 15 秒 187/200，第 25 秒 172/200 | 待实测 |
| 负载期间成功订单速率与积压 | 见原每秒 samples | 待实测 |
| 停流追平时间 | 历史约 7–33 秒，按具体档/轮核对 | 待实测 |
| 唯一性、库存、消息关联 | 原场景最终对账通过 | 待实测 |

结论必须能补齐：**原拐点是什么（环境和口径）→等待在哪里（线程栈）→减少了哪些同步工作（消融与计数）→新拐点是多少（同机多轮）→代价是什么（关闭窗口、缓存内存、后端积压）。** 若只能提高突发入队而落库没提高，就明确写“入口突发承载提高，端到端持续完成能力未证明提高”。
