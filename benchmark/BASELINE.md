# MiniSeckill 优化前基线(Mac ARM64 单机)

> 本文件记录**优化前**(阶段1 D1-D7 / 阶段2 A1-A5 改动之前)的本机压测基线,作为优化后同参复测的对比锚点。
> 数据全部来自本机真实 k6 输出与 MySQL 对账,未测项标"n/a"。

## ⚠️ 口径声明

- 本基线在 **macOS ARM64(Apple M1)** 单机跑;历史 `REPORT.md` 是 **Windows x86(Ryzen 7 6800H)** 机跑的。**两者跨机器、跨架构,绝对值不可直接比较**。本轮优化效果只用"本机基线 vs 本机优化后"同机对比。
- 基线代码 = 当前工作区代码 + 一处阻断性回归修复(`SeckillMessageMapper` 的 `markSending`/`markSentFromSending` 两个方法 SQL 被写反,导致订单永不落库;已按初始 commit 正确逻辑还原)。除此之外未做任何 D1-D7/A1-A5 优化。此修复是"让链路能正常工作"的前提,不是优化项。
- 应用以 `--spring.profiles.active=100k --seckill.rate-limit.enabled=false --seckill.anti-brush.enabled=false --seckill.dynamic-rate-limit.enabled=false` 启动(与旧 REPORT 同口径:关限流/anti-brush,测下单链路本身)。

## 测试环境(本机实测)

| 项 | 值 |
|---|---|
| OS | macOS 26.5.1 arm64 |
| CPU / 内存 | Apple M1 8核 / 16GB |
| Docker / Compose | 29.5.3 / v5.1.4 |
| k6 | v2.1.0 (darwin/arm64) |
| MySQL | 8.0.46(容器) |
| Redis | 7.4.9(容器) |
| RabbitMQ | 3.13.7(容器) |
| JDK(打包+运行) | eclipse-temurin-17(容器 maven:3.9.9-eclipse-temurin-17) |
| 应用 | Spring Boot 3.3.5,单进程,端口 18080,容器内 JRE17 |
| 依赖部署 | docker compose(mysql/redis/rabbitmq),应用容器接入同网络 |

profile=100k 关键参数:bucket-count=128、mysql-segment=128、mq consumer 8-32、prefetch 100、hikari pool 40。

## 压测场景与结果

复现:`BASE_URL=http://localhost:18080 K6=~/bin/k6 bash benchmark/run-suite.sh baseline-mac`
每场景前自动 reset(清库表+Redis+MQ)+ init/warmup。原始文件在 `benchmark/results/*baseline-mac*`。

### 场景1 · unique 洪峰(不同用户,库存 1000)

- 参数:rate 75/s × 60s,mode=unique
- http_reqs **5555**(含 token+order 两跳),吞吐 92.57 req/s
- **order_queued 1000**(= 库存,成功入队)、order_sold_out 3501、**system_error 0 / 错误率 0**
- 入队响应 queue_response_duration:avg 7.04ms,med 6.19ms,**p95 11.90ms**,p99 21.27ms,max 40.34ms
- http_req_duration:med 4.03ms,p95 7.99ms,p99 19.95ms

### 场景2 · duplicate 防重(1000 用户循环重复,库存 1000)

- 参数:rate 75/s × 60s,mode=duplicate
- http_reqs 5500,吞吐 91.64 req/s
- **order_queued 1000**、**system_error 0 / 错误率 0**
- 对账:1000 订单 = 1000 distinct users(每人只成 1 单),**重复订单组 0**,消息 1000 全 CONSUMED,consumed==orders
- 入队响应:med 4.99ms,p95 7.39ms,p99 10.81ms

### 场景3 · 售罄尾部(小库存高压,库存 100)

- 参数:rate 300/s × 30s,mode=unique
- http_reqs 9404,吞吐 313.44 req/s
- **order_queued 100**(= 库存)、order_sold_out 8900、**system_error 0 / 错误率 0**
- 入队响应:avg 38.93ms,**p95 57.21ms**,p99 65.08ms,max 71.02ms
- http_req_duration:med 1.74ms,p95 20.63ms,p99 221.66ms(售罄尾部长尾)

## 正确性(MySQL 对账,零超卖零重复)

- 售罄场景终态:订单 100 = success 100 = 库存 sold_count 100,available_stock 0,消息全 CONSUMED,重复订单组 0
- duplicate 场景:1000 订单 = 1000 用户,重复组 0,local_messages=consumed_messages=success_orders=1000
- 三场景 system_error_rate 均为 0,Redis/MySQL 库存收敛一致

## 待优化项在基线中的表现(优化后对比看点)

- **D4 下单日志同步写库**:当前 placeOrder 链路每请求同步 insert seckill_log 多次 → 优化后看吞吐/RT 提升(将加 async-log 开/关对照)
- **D2/D3 限流打库**:本基线关了限流/anti-brush,未体现;阶段3复测将补一组"限流+anti-brush 全开"场景,对比 MySQL Com_select 增量
- **A3 售罄尾部**:场景3 的 p99 长尾(221ms)部分来自分片售罄多次 Redis RTT → 优化后看尾部下降
- **A1 限流临界**:固定窗口,复测全开场景验证滑动窗口改造

---
基线固化时间:2026-07-12 21:45,Asia/Shanghai。
