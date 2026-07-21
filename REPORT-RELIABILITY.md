# MiniSeckill 可靠性验证报告

测试日期：2026-07-21（Asia/Shanghai）  
测试环境：macOS / Apple M1 8 核 / 16 GiB，Docker 29.5.3，k6 v2.1.0  
验证范围：四实例订单号唯一性、Redis 故障恢复、RabbitMQ 积压追赶、长时间稳定性、Prometheus 告警

> 本报告验证的是单机容器环境中的正确性、恢复语义和可复现性，不把结果外推为生产集群容量。

## 1. 结论摘要

- 四个应用实例经 Nginx `least_conn` 共同承压，workerId `0` 到 `3` 都实际生成订单；unique、duplicate、selltail 三个场景的 `order_id` 重复数均为 0，用户重复订单组均为 0，系统错误率均为 0。
- Redis 停止 180 秒期间入口 fail-closed：6000 次迭代中 3918 次收到系统级拒绝，未绕过 Redis 直写数据库；恢复时 MySQL 仍有 3803 件可用库存，四实例串行重建后继续放量，最终 2082 单、剩余库存 2918、队列清零、零重复。
- 消费者关闭时形成 1000 条 ready 消息；恢复消费者后 9 秒清空，最终 1000 条消息对应 1000 个成功订单，无重复、无死信。
- `MiniSeckillHigh5xxRate` 在 Redis 故障窗内真实进入 firing，采样文件记录到 11 个 firing 样本；正常多实例轮 Prometheus 采集到 admission 与 MQ 业务指标。
- 新增 8 条显式单测，Maven 实跑 88 条单元测试；JaCoCo 实测行覆盖率 49.19%，CI 门禁从 35% 提高到 45%。

## 2. 环境与口径

### 2.1 多实例拓扑

```text
k6 -> Nginx :80 -> app-1..app-4 :8080
                       |     |     |
                     workerId 0..3
                       \     |    /
                 MySQL + Redis + RabbitMQ
```

- 四个应用 JVM 均限制为 `-Xmx512m`，避免 16 GiB 本机被默认堆上限挤满。
- MySQL `max_connections` 调为 300，覆盖四实例各 40 个 Hikari 连接的理论上限。
- `nginx-bench.conf` 把 IP 边缘限流提高到 1000 req/s。原因是一次 k6 迭代包含 token 与 order 两个 HTTP 请求，selltail 的 300 iterations/s 实际约为 600 req/s；若继续使用日常 200 req/s 阈值，测到的是 Nginx 拒绝而不是应用正确性。
- 多实例轮保留 100k profile 的业务限流和 anti-brush，不与 `REPORT.md` 的单实例优化前后延迟做横向比较。

### 2.2 事实源与收敛口径

MySQL 分段库存开启时，`sku_stock_segment` 是扣减事实，`sku_stock` 是定时对账同步的汇总视图；Redis 分片开启时，bucket 合计是入口库存事实，总量 key 是汇总值。演练完成后脚本会等待“成功订单数 = MySQL 汇总已售数”且“Redis 总量 key = bucket 合计”，再保存最终 verify，避免把 60 秒对账窗口内的瞬时差异误写成结论。

## 3. 四实例正确性

| 场景 | 到达率与时长 | 初始库存 | 成功入队 | 系统错误率 | 订单号重复 | 用户重复订单组 |
|---|---:|---:|---:|---:|---:|---:|
| unique | 75/s × 60s | 1000 | 1000 | 0 | 0 | 0 |
| duplicate | 75/s × 60s | 1000 | 1000 | 0 | 0 | 0 |
| selltail | 300/s × 30s | 100 | 100 | 0 | 0 | 0 |

代表性的 unique 轮中，1000 个订单按 workerId 分布为：`0=252、1=251、2=249、3=248`。duplicate 轮为 `254、249、249、248`；并非只有一个实例在工作，也不是单测里模拟不同 workerId。

正确性 SQL 同时核对：

1. `COUNT(*) = COUNT(DISTINCT order_id)`；
2. `activity_id + user_id + sku_id` 重复组为 0；
3. 成功订单数等于 CONSUMED 消息数；
4. RabbitMQ order/dead 队列最终均为 0。

证据：

- `benchmark/evidence/mac-arm64/multi-unique-summary.json`
- `benchmark/evidence/mac-arm64/multi-unique-verify.txt`
- `benchmark/evidence/mac-arm64/multi-duplicate-summary.json`
- `benchmark/evidence/mac-arm64/multi-duplicate-verify.txt`
- `benchmark/evidence/mac-arm64/multi-selltail-summary.json`
- `benchmark/evidence/mac-arm64/multi-selltail-verify.txt`

## 4. Redis 故障与恢复

### 4.1 时间线

| 时间 | 动作与状态 |
|---|---|
| 16:56:43 | 初始化库存 5000，开始 20 iterations/s、总时长 300 秒的持续压力 |
| 16:57:45 | 停止 `mini-seckill-redis` |
| 约 16:58:02–16:58:06 | 四实例分别达到连续失败阈值，进入本地恢复态 |
| 17:00:45 | 启动 Redis |
| 17:01:01 | 严格串行调用四实例恢复接口；前三次期望库存为 3803，最后一次为 3802（串行恢复期间已有一个新订单提交） |
| 17:01:45 | k6 完成，等待定时对账收敛 |
| 17:04:45 | 汇总库存、分段库存、Redis 总量和 bucket 合计全部收敛 |

### 4.2 最终结果

| 指标 | 结果 |
|---|---:|
| k6 iterations | 6000 |
| 故障窗内系统级拒绝 | 3918 |
| 成功订单 | 2082 |
| MySQL 初始/已售/可用 | 5000 / 2082 / 2918 |
| Redis 总量 / bucket 合计 | 2918 / 2918 |
| order_id 重复 | 0 |
| 用户重复订单组 | 0 |
| CONSUMED 消息 / 成功订单 | 2082 / 2082 |
| RabbitMQ order/dead 队列 | 0 / 0 |

故障期间产生的 `RECONCILE_FAILED` 与短暂 `MYSQL_ORDER_STOCK_MISMATCH` 补偿记录属于可观测的中间态；最终事实收敛后没有消息悬挂或超卖。恢复不是无感切换：入口明确返回 503，恢复完成后才继续放量。

多实例恢复态目前仍保存在各 JVM 内存中，因此脚本必须逐实例检查并串行恢复。代码同时修复了一个边界：恢复锁被其他实例占用时不再把 `skipped` 误判为“恢复完成”并提前退出恢复态。

证据：

- `benchmark/evidence/mac-arm64/fault-summary.json`
- `benchmark/evidence/mac-arm64/fault-timeline.txt`
- `benchmark/evidence/mac-arm64/fault-alerts.jsonl`
- `benchmark/evidence/mac-arm64/fault-verify.txt`

## 5. RabbitMQ 积压追赶

P1 启动应用但设置 `seckill.mq-consumer.auto-startup=false`，并在压测前通过 `rabbitmqctl list_consumers` 断言消费者数量确实为 0。75 iterations/s 持续 60 秒后：

- 1000 个请求成功进入本地消息表与 RabbitMQ；
- order queue 的 `messages_ready=1000`；
- 此时 MySQL 尚未创建订单，证明请求没有被隐藏的消费者提前处理。

P2 重新启动消费者后，ready 消息在 9 秒内降到 0。对账收敛后：成功订单 1000、CONSUMED 消息 1000、order_id 重复 0、用户重复订单组 0、MySQL 与 Redis 剩余库存均为 0。

该结果证明的是“1000 条积压可在当前单机环境追平并保持一致”，不是消费者线性扩容或生产积压容量证明。若积压持续超过默认 10 分钟，OrderTimeoutJob 会把长期 SENT 消息标为 TIMEOUT，这是另一条明确的业务边界。

证据：

- `benchmark/evidence/mac-arm64/backlog-summary.json`
- `benchmark/evidence/mac-arm64/backlog-timeline.txt`
- `benchmark/evidence/mac-arm64/backlog-verify.txt`

## 6. 45 分钟稳定性长跑

单实例以 10 iterations/s 持续 45 分钟,初始库存 30000,完整完成 27001 次迭代和 54002 次 HTTP 请求,无 dropped iterations、系统错误率为 0。成功入队 27001 次,最终数据库成功订单 27001、CONSUMED 消息 27001、剩余库存 2999,order_id 与用户订单重复均为 0。

每分钟共采到 45 个资源样本:JVM heap 在 6.0–132.0 MiB 之间波动,末次为 44.0 MiB,没有随请求量单调增长;Hikari active 连接最大为 3,pending 最大为 0。`seckill_message` 行数随订单从 0 线性增长到采样时的 26499,最终对账为 27001,这是业务留痕增长而不是未释放资源。

| 指标 | 结果 |
|---|---:|
| iterations / HTTP requests | 27001 / 54002 |
| HTTP p95 / p99 | 12.56 ms / 34.31 ms |
| 成功入队 p95 / p99 | 13.33 ms / 29.96 ms |
| system_error_rate / dropped | 0 / 0 |
| heap min / max / last | 6.0 / 132.0 / 44.0 MiB |
| Hikari active max / pending max | 3 / 0 |
| 成功订单 / 剩余库存 | 27001 / 2999 |

证据:

- `benchmark/evidence/mac-arm64/soak-summary.json`
- `benchmark/evidence/mac-arm64/soak-metrics.csv`
- `benchmark/evidence/mac-arm64/soak-timeline.txt`
- `benchmark/evidence/mac-arm64/soak-verify.txt`

## 7. Prometheus 与告警

监控栈使用 Prometheus 5 秒抓取四个 app 的 `/actuator/prometheus`，Grafana dashboard 展示 admission、MQ、Redis、订单、HTTP 延迟和 5xx。正常多实例轮的 Prometheus query_range 采集到 admission 5 组结果和 MQ 4 组结果；Redis 故障轮的 `MiniSeckillHigh5xxRate` 连续满足阈值并进入 firing，JSONL 中保存了 11 个 firing 样本。

HTTP server histogram 已显式开启，避免 Grafana p95 查询只有 count/sum 而没有 bucket 数据。告警证据只证明规则在本地演练中可触发，不等于已有长期值班、通知渠道或生产阈值校准。

证据：

- `benchmark/evidence/mac-arm64/monitoring-admission.json`
- `benchmark/evidence/mac-arm64/monitoring-mq.json`
- `benchmark/evidence/mac-arm64/monitoring-api-5xx.json`
- `benchmark/evidence/mac-arm64/monitoring-api-p95.json`
- `benchmark/evidence/mac-arm64/monitoring-alerts.json`
- `benchmark/evidence/mac-arm64/monitoring-summary.txt`
- `benchmark/evidence/mac-arm64/monitoring-histogram-summary.txt`

## 8. 复现入口

```bash
# 四实例 + Nginx + Prometheus/Grafana
docker compose \
  -f docker-compose.yml \
  -f docker-compose.app-scale.yml \
  -f docker-compose.nginx.yml \
  -f docker-compose.monitoring.yml \
  -f docker-compose.bench-limits.yml \
  up -d

# 多实例主场景
BASE_URL=http://localhost:80 ./benchmark/run-suite.sh multi

# Redis 故障依赖四实例都处于恢复态
BASE_URL=http://localhost:80 ./benchmark/run-fault-drill.sh

# backlog/soak 必须在没有其它应用消费者的环境运行
# 保留 MySQL/Redis/RabbitMQ,停止四实例和 Nginx
docker compose \
  -f docker-compose.yml \
  -f docker-compose.app-scale.yml \
  -f docker-compose.nginx.yml \
  -f docker-compose.monitoring.yml \
  -f docker-compose.bench-limits.yml \
  stop app-1 app-2 app-3 app-4 nginx
./benchmark/run-backlog-drill.sh
./benchmark/run-soak.sh

# 证据完整性
./scripts/check-evidence.sh
```

所有脚本默认只操作本项目固定容器名和活动 `1` / SKU `1001`，应在隔离的本地演练环境执行。
