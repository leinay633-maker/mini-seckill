# 压测 QA

## Q1：当前主报告的环境是什么？

当前 `REPORT.md` 使用 2026-07-13 的 Mac ARM64 同机对比：

- macOS 26.5.1，Apple M1，8 核，16 GiB。
- Docker Client/Server 29.5.3，Compose v5.1.4。
- k6 v2.1.0，darwin/arm64。
- 应用 Java 17.0.15，Spring Boot 3.3.5。
- MySQL 8.0.46、Redis 7.4.9、RabbitMQ 3.13.7。
- 应用监听 `18080`。

旧 Windows 11 / AMD / k6 v2.0.0 数据是真实历史记录，但跨机器不可比，不参与当前优化百分比。

## Q2：公平性怎么保证？

基线和优化版都使用 `benchmark/run-suite.sh`，唯一预期变量是应用代码。每轮前执行同一个 `reset-env.sh`：清 MySQL 业务表、清 Redis `seckill:*`、purge RabbitMQ 队列，然后重新初始化和预热相同库存。

三个主场景参数固定：

| 场景 | 到达率 | 时长 | 库存 | 用户口径 |
|---|---:|---:|---:|---|
| unique | 75/s | 60s | 1000 | 每次迭代不同用户 |
| duplicate | 75/s | 60s | 1000 | 1000 用户循环 |
| selltail | 300/s | 30s | 100 | 每次迭代不同用户 |

每个版本、每个场景跑 3 次，各指标排序后取中位数。

## Q3：为什么主场景关闭限流和 anti-brush？

主场景要隔离订单链路本身，所以两版都关闭业务限流、anti-brush 和动态限流。这样比较的是 Redis 幂等与库存、本地消息、MQ、MySQL 落库等共同路径。

D2/D3 的收益只在开关开启时明显，因此另跑“限流 + anti-brush + 动态限流 + 隐藏 path 全开”的对照组，而且基线和优化版都跑一份，避免只测优化版造成不公平。

## Q4：三个主场景结果是什么？

| 场景 | 基线 | 优化后 | 变化 |
|---|---:|---:|---:|
| unique 成功入队 p95 | 8.17 ms | 5.49 ms | -32.8% |
| duplicate 成功入队 p95 | 7.92 ms | 4.76 ms | -39.9% |
| selltail 整体 HTTP p99 | 98.27 ms | 12.04 ms | -87.7% |
| selltail 成功入队 p95 | 39.51 ms | 20.79 ms | -47.4% |

三个场景的三轮中位数 `system_error_rate` 都是 0，没有 dropped iterations。

## Q5：为什么 `http_reqs` 高于 iterations？

每个 iteration 先请求 token，拿到 token 后才请求下单。库存售罄后，大量请求会直接在 token 阶段结束，所以 `http_reqs` 是 token 与下单请求之和，不能直接当成“下单吞吐”。

报告优先看：到达率是否完成、业务分类、系统错误、延迟和最终数据库/MQ 对账。

## Q6：D2/D3 怎么从数据里看出来？

全开单轮对照中：

- MySQL `Com_select` 增量：23,422 → 17,920，下降 23.5%。
- 动态限流规则查询：1999 → 7，下降 99.6%。
- 用户订单事实查询：4501 → 1000，下降 77.8%。

D2 把“数据库无动态规则”也缓存为 `Optional.empty()`；D3 在 token 热路径先查 Redis 订单状态和用户 SKU key，只有 Redis 无事实时才回源 MySQL。

## Q7：异步日志收益怎么证明？

使用同一个优化 jar，只切换 `seckill.async-log.enabled`，关闭组和开启组都跑 unique 三轮：

- 成功入队 p95：14.47 ms → 5.49 ms，下降 62.1%。
- 成功入队 p99：26.40 ms → 8.64 ms，下降 67.3%。

这只能证明高频观察日志异步化降低了入口尾延迟，不能说本地消息和订单事实也异步写；可靠性事实仍同步落库。

## Q8：怎么判断零超卖和零重复？

至少同时检查：

- `success_orders <= initial_stock`。
- MySQL 分段库存合计不为负。
- `duplicate_order_groups=0`。
- 消息状态与订单总记录能解释一致。
- RabbitMQ order/dead 队列最终状态。

MySQL 分段库存开启时，`sku_stock_segment` 是扣减事实，`sku_stock` 是定时同步的汇总视图；采样撞上同步窗口时汇总值可能暂时滞后，不能只看汇总表一列下结论。

## Q9：为什么不能说“入口绝不多受理”？

个别轮次出现过入口受理略高于最终成功数：

- 基线 unique r1 受理 1005，最终成功 1000，仍有非最终消息和 1 条 dead queue。
- 优化版 unique r3 受理 1002，最终是 1000 个成功订单 + 2 个 FAILED 订单，1002 条消息进入消费终态。

准确说法是“数据库成功订单未超过库存、失败结果可追踪、重复订单组为 0”，而不是把入口准入和最终成单混为一谈。

## Q10：A3 为什么主要改善售罄尾部？

旧分片扣减可能需要逐桶尝试，多次 Redis 往返会放大尾延迟。优化后单机 Redis 把全部 bucket 传给一次 Lua，脚本内部环形扫描并原子扣减；售罄时也能更快形成统一状态。

因此 selltail 的 p50 基本不变，但 p95/p99 明显下降。Redis Cluster 因跨 slot 限制会关闭单 Lua并回退，这份单机结果不能外推到 Cluster。

## Q11：怎么复现？

```bash
BASE_URL=http://localhost:18080 K6="$HOME/bin/k6" \
  bash benchmark/run-suite.sh baseline-mac-r1

BASE_URL=http://localhost:18080 K6="$HOME/bin/k6" \
  bash benchmark/run-suite.sh optimized-mac-r1
```

完整对比要分别跑 r1、r2、r3。`reset-env.sh` 会清理测试数据和队列，只能在隔离环境使用。

## Q12：简历上怎么写？

可以写：

> 在 Apple M1 单机 Docker 环境完成秒杀链路同机同参三轮压测；优化后不同用户场景成功入队 p95 下降 32.8%，重复请求场景下降 39.9%，售罄尾部 HTTP p99 下降 87.7%；全开防刷对照中动态规则查询下降 99.6%、用户订单事实查询下降 77.8%；数据库成功订单未超过库存，重复订单组为 0。

## Q13：哪些说法不能写？

- 10 万 QPS、百万并发。
- 跨 Windows 和 Mac 直接计算优化比例。
- Redis 无感恢复。
- 入口每次都恰好只受理库存数量。
- 消息绝不丢、生产级高可用。
- 单机结果能代表 Redis Cluster 或生产集群容量。
