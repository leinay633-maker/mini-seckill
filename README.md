# MiniSeckill：高并发秒杀订单系统

MiniSeckill 是一个面向 Java 服务端研发面试的后端样板项目。它不是完整电商系统，而是聚焦“秒杀下单”主链路，用 Spring Boot、Redis、MySQL、RabbitMQ 展示活动状态、库存倍数资格 token、防刷限流、Redis Lua、库存分片、异步削峰、事务兜底、可靠消息、Redis 宕机恢复和最终一致性。

## 1. 项目背景

秒杀系统的核心矛盾是入口流量集中、库存是热点、重复请求多、数据库不能直接承接峰值。项目去掉登录、支付、优惠券和复杂商品系统，只保留服务端面试最值得讲的链路。

```text
活动状态校验
-> 获取短 TTL 秒杀 token
-> 库存倍数资格池控制 token 发放总量
-> Redis Lua 限流
-> Redis Set-N-X 幂等
-> Redis Lua 扣分片库存
-> 本地消息表记录待投递消息
-> RabbitMQ 异步削峰
-> 消费者 MySQL 事务创建订单并扣库存
-> RabbitMQ confirm / return 回调更新消息状态
-> 定时任务做消息重投、排队超时、库存对账
-> Redis 宕机后进入恢复态，按 MySQL 可用库存 - 未完成消息数重建 Redis 库存
```

## 2. 技术栈

- Java 17
- Spring Boot 3.x
- Maven
- MyBatis
- MySQL 8
- Redis / Redis Cluster / Redis Lua / Redisson
- RabbitMQ management
- Spring Boot Actuator / Micrometer / Prometheus
- Caffeine
- Docker Compose
- Nginx 示例配置
- k6 压测脚本

## 3. 架构图

```text
Client / Nginx
     |
     v
ActivityController  管理活动状态
     |
     v
SeckillController
     |
     +-- GET /api/seckill/token
     |     +-- 活动状态校验
     |     +-- Redis 写短 TTL token
     |
     +-- POST /api/seckill/order
           +-- Lua 限流
           +-- token 校验并删除，防重放
           +-- Set-N-X 用户 SKU 幂等
           +-- Lua 扣 Redis 分片库存
           +-- 写 seckill_message
           +-- 投递 RabbitMQ
                   |
                   v
              SeckillConsumer
                   |
                   v
              MySQL Transaction
              插入订单 + 条件扣库存 + 写日志
```

## 4. 核心链路

1. 活动通过 `seckill_activity` 管理，只有“进行中且处于时间窗口内”的活动允许下单。
2. 用户先请求 `/api/seckill/token`，服务端生成短 TTL token，降低脚本直接刷下单接口的风险。
3. token 发放受资格池控制，默认最多发放“Redis 入场库存 * 3”的 token，超出后在 token 阶段快速拒绝。
4. 下单接口先执行 Redis Lua 限流，按活动 SKU、用户、IP 三个维度做固定窗口限流。
5. token 校验通过后删除 token，避免同一个 token 重放。
6. 使用 Redis `Set-N-X` 写用户 SKU 幂等 key，挡住重复点击和超时重试。
7. Redis 库存按 bucket 分片，Lua 在某个分片 key 上原子判断并扣减。
8. Redis 扣成功后写 `seckill_message` 本地消息表，再投递 RabbitMQ。
9. RabbitMQ confirm ack 后消息进入 `SENT`，return 或 nack 后进入可重试状态。
10. 消费者在 MySQL 事务里插入订单并扣库存，数据库用联合唯一索引和“可用库存大于 0”兜底。
11. 定时任务负责 MQ 重投、排队超时、Redis/MySQL 库存对账。
12. Redis 健康检查连续失败后，入口进入恢复态；恢复接口会按“数据库可用库存 - 未完成 MQ 消息数”重建 Redis 总库存、分片库存和 token 资格池。

## 5. 启动方式

```bash
docker compose up -d
mvn spring-boot:run
```

默认端口：

- 服务端：`http://localhost:8080`
- MySQL：`localhost:3306`
- Redis：`localhost:6379`
- RabbitMQ：`localhost:5672`
- RabbitMQ Management：`http://localhost:15672`

默认账号：

- MySQL：`miniseckill / miniseckill`，root 密码 `root`
- RabbitMQ：`guest / guest`

## 6. 接口说明

### 活动管理

```bash
curl -X POST "http://localhost:8080/api/activity/create?activityId=2&name=测试秒杀&startTime=2026-01-01T00:00:00&endTime=2099-12-31T23:59:59"
curl -X POST "http://localhost:8080/api/activity/start?activityId=2"
curl "http://localhost:8080/api/activity/query?activityId=2"
curl -X POST "http://localhost:8080/api/activity/close?activityId=2"
```

`sql/init.sql` 会默认创建一个 `activityId=1` 的进行中活动，方便本地演示。

### 初始化库存

```bash
curl -X POST "http://localhost:8080/api/seckill/init?activityId=1&skuId=1001&stock=100"
```

作用：

- upsert MySQL 库存事实表
- 写 Redis 总库存 key
- 按配置写 Redis 分片库存 key
- 写 Redis token 资格池 key，默认额度为库存数的 3 倍
- 使用 Redisson 分布式锁保护同一活动 SKU 的初始化动作

### 库存预热

```bash
curl -X POST "http://localhost:8080/api/seckill/warmup?activityId=1&skuId=1001"
```

作用：从 MySQL 读取可用库存，重新写入 Redis 总库存、分片库存和 token 资格池。面试时可以讲成活动开始前的库存预热简化版。

### 获取秒杀 token

```bash
curl "http://localhost:8080/api/seckill/token?activityId=1&userId=10001&skuId=1001"
```

返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "activityId": 1,
    "userId": 10001,
    "skuId": 1001,
    "token": "短 TTL token",
    "expiresInSeconds": 120
  }
}
```

### 秒杀下单

```bash
curl -X POST "http://localhost:8080/api/seckill/order" \
  -H "Content-Type: application/json" \
  -d "{\"activityId\":1,\"userId\":10001,\"skuId\":1001,\"token\":\"上一步返回的 token\"}"
```

成功进入队列时返回：

```json
{
  "code": 0,
  "message": "排队中"
}
```

### 查询订单

```bash
curl "http://localhost:8080/api/order/query?activityId=1&userId=10001&skuId=1001"
```

状态包括：

- `未下单`
- `排队中`
- `成功`
- `失败`
- `已关闭`
- `排队超时`

### 查询库存

```bash
curl "http://localhost:8080/api/seckill/stock?activityId=1&skuId=1001"
```

返回 Redis 分片库存汇总、MySQL 总库存、MySQL 可用库存、已售数量。

### Redis 宕机恢复

```bash
curl "http://localhost:8080/api/recovery/redis/status"
curl -X POST "http://localhost:8080/api/recovery/redis"
```

恢复逻辑：

- Redis 健康检查连续失败达到阈值后，本机进入 `recovering=true`。
- 恢复态下，获取 token 和秒杀下单直接返回 503，入口 fail closed。
- Redis 重启后，执行恢复接口，从 MySQL 扫描库存事实表。
- 对每个活动 SKU 计算：`expectedRedisStock = max(0, mysql.availableStock - unfinishedMessages)`。
- 同时重建 Redis 总库存 key、bucket 分片库存 key 和 token 资格池 key。

故障演练脚本：

```powershell
.\scripts\fault-stop-redis.ps1
.\scripts\fault-start-redis.ps1
.\scripts\recovery-check.ps1
```

## 7. 数据库设计

核心表：

- `seckill_activity`：活动状态表
- `sku_stock`：活动 SKU 汇总库存表
- `sku_stock_segment`：MySQL 分段库存事实表，降低单行库存热点
- `seckill_order`：订单事实表
- `seckill_log`：请求和异常日志表
- `seckill_message`：本地消息表
- `seckill_rate_limit_rule`：动态限流规则表
- `seckill_compensation`：补偿记录表

关键索引：

- `seckill_activity.uk_activity_id(activity_id)`
- `sku_stock.uk_activity_sku(activity_id, sku_id)`
- `sku_stock_segment.uk_activity_sku_segment(activity_id, sku_id, segment_id)`
- `seckill_order.uk_activity_user_sku(activity_id, user_id, sku_id)`
- `seckill_message.uk_request_id(request_id)`
- `seckill_rate_limit_rule.uk_activity_sku(activity_id, sku_id)`

MySQL 分段库存扣减 SQL：

```sql
UPDATE sku_stock_segment
SET available_stock = available_stock - 1,
    sold_count = sold_count + 1,
    updated_at = NOW()
WHERE activity_id = ?
  AND sku_id = ?
  AND segment_id = ?
  AND available_stock > 0;
```

说明：

- 默认开启 `seckill.mysql-stock-segment.enabled=true`。
- 初始化库存时会把库存拆到多个 segment 行。
- 消费者事务内创建订单并扣减一个 segment 行，降低 MySQL 单行热点。
- `sku_stock` 作为汇总表保留，初始化和对账任务会同步汇总值。
- 如果某个旧 SKU 还没有 segment 行，消费者会临时回退到旧的 `sku_stock` 单行扣减。

旧库升级可以执行：

```bash
mysql -uroot -p < sql/upgrade_v2.sql
mysql -uroot -p < sql/upgrade_v3.sql
mysql -uroot -p < sql/upgrade_v4.sql
```

## 8. Redis key 设计

```text
seckill:stock:{activityId}:{skuId}
seckill:stock:{activityId}:{skuId}:bucket:{bucket}
seckill:user:{activityId}:{userId}:sku:{skuId}
seckill:token:{activityId}:{userId}:{skuId}
seckill:token:quota:{activityId}:{skuId}
seckill:order:status:{activityId}:{userId}:{skuId}
seckill:rate:sku:{activityId}:{skuId}
seckill:rate:user:{activityId}:{userId}
seckill:rate:ip:{activityId}:{clientIp}
seckill:lock:stock:init:{activityId}:{skuId}
seckill:lock:reconcile:{activityId}:{skuId}
seckill:lock:redis:recovery:{activityId}:{skuId}
```

Lua 脚本：

```text
src/main/resources/lua/seckill_stock.lua
src/main/resources/lua/rate_limit.lua
```

## 8.1 Redis Cluster 模式

默认启动方式仍然是单 Redis，方便本地快速演示。Redis Cluster 模式已经单独提供：

- `application-redis-cluster.yml`：Spring Data Redis Cluster 节点配置
- `RedissonConfig`：自动识别 `spring.data.redis.cluster.nodes`，切换 Redisson Cluster 模式
- `docker-compose.redis-cluster.yml`：6 节点 Redis Cluster，3 master + 3 replica
- `scripts/redis-cluster-check.ps1`：查看 Cluster 状态和 Cluster 版应用状态

启动 Redis Cluster 演示版：

```bash
mvn package
docker compose -f docker-compose.yml -f docker-compose.redis-cluster.yml up -d --build
```

Cluster 版应用端口：

```text
http://localhost:8090
```

检查 Cluster：

```powershell
.\scripts\redis-cluster-check.ps1
```

说明：

- Redis Cluster 解决多节点容量和高可用，不会自动解决单个超级热 key。
- 本项目保留 `seckill:stock:{activityId}:{skuId}:bucket:{bucket}` 库存 bucket，生产级可以把不同 bucket key 分散到多个 Redis master。
- Cluster 下避免跨 slot 多 key 命令，代码里已经把 bucket 批量删除改为逐个 key 删除。
- Lua 扣库存仍然只操作单个库存 key，因此兼容 Redis Cluster。

## 9. RabbitMQ 设计

核心类：

- `RabbitMQConfig`
- `SeckillProducer`
- `SeckillConsumer`
- `SeckillMessageRetryJob`

可靠消息骨架：

- Redis 扣库存成功后先写 `seckill_message`
- 发送前标记 `SENDING`
- 生产者发送消息时携带 `CorrelationData`
- confirm ack 后标记 `SENT`
- confirm nack 或 return 后标记可重试状态
- 消费成功后标记 `CONSUMED`
- 发送失败按指数退避写入 `next_retry_at`
- 超过最大重试次数后标记 `DEAD`
- 消费异常标记 `DEAD` 并 nack 到死信队列
- 重投任务扫描可重试消息，管理接口可回放 DEAD 消息
- 消费者并发和 prefetch 通过 `seckill.mq-consumer` 配置控制，避免无限扩消费者把 MySQL 打满

状态机口径：

```text
PENDING -> SENDING -> SENT -> CONSUMED
                    \-> FAILED / CONFIRM_FAILED / RETURNED -> DEAD
DEAD / TIMEOUT / FAILED -> REPLAYED -> SENDING
```

回放接口：

```http
POST /api/admin/messages/replay?requestId={requestId}
POST /api/admin/messages/replay-dead?limit=20
```

当前项目不直接消费 RabbitMQ 死信队列里的原始消息，而是用本地消息表作为回放依据。这样更容易在个人项目里演示可靠消息闭环；生产级会增加死信告警、后台审核、操作审计和批量补偿。

## 9.1 动态限流、售罄标记和可观测性

动态限流接口：

```http
POST /api/admin/rate-limit
Content-Type: application/json

{
  "activityId": 1,
  "skuId": 1001,
  "enabled": true,
  "windowSeconds": 1,
  "skuLimit": 1000,
  "userLimit": 2,
  "ipLimit": 100
}
```

查询实际生效规则：

```http
GET /api/admin/rate-limit?activityId=1&skuId=1001
GET /api/admin/rate-limit/raw?activityId=1&skuId=1001
```

规则来源：

- 默认走 `application.yml`
- 命中 `seckill_rate_limit_rule` 后走 DB 动态规则
- 应用内用 Caffeine 做短 TTL 缓存，避免每个请求查 MySQL

售罄本地标记：

- Redis Lua 返回库存不足时，本机 Caffeine 标记该 SKU 短时间售罄
- 后续请求先被本机挡掉，减少 Redis 空打
- 初始化、预热、Redis 恢复、对账修复库存时会清理售罄标记
- 这是入口优化，不是最终事实；最终事实仍然是 MySQL 订单和库存

Actuator / Prometheus：

```http
GET /actuator/health
GET /actuator/metrics
GET /actuator/prometheus
```

自定义指标包括：

- `seckill_admission_total`
- `seckill_redis_stock_total`
- `seckill_mq_total`
- `seckill_order_total`
- `seckill_replay_total`

## 9.2 补偿、对账和故障演练

补偿记录接口：

```http
GET /api/admin/compensations?limit=20
```

会记录以下类型：

- Redis 库存修复
- Redis 宕机恢复重建
- MySQL 订单数和库存 soldCount 不一致
- MQ 重试耗尽
- 消息回放
- 排队超时关闭

对账任务：

- 按 `sku_stock.id` 分页扫描，避免只扫最近更新记录
- 开启 MySQL 分段库存时，按 segment 汇总可用库存和已售数量
- Redis 期望库存 = MySQL 可用库存 - 未完成消息数
- 修复 Redis 总库存和 bucket 库存
- 对账动作写入 `seckill_compensation`

Redis Cluster 故障脚本：

```powershell
.\scripts\fault-stop-redis-cluster-master.ps1 mini-seckill-redis-cluster-7000
.\scripts\fault-start-redis-cluster-node.ps1 mini-seckill-redis-cluster-7000
```

面试口径要说清楚：Cluster 可以提升容量和故障切换能力，但超级热 key 仍然要靠库存 bucket 拆分、限流和 token 资格池处理。

## 10. 10 万并发方向说明

这里的“10 万并发”指 10 万用户同时涌入活动入口，不是 10 万请求每秒都完整下单成功，更不是 MySQL 每秒写 10 万订单。

本项目已补的方向：

- token 资格池：默认只发 `库存 * token-quota-multiplier` 个短 TTL token，大量请求在 token 阶段快速失败。
- Redis 库存分片：默认 bucket 数从 8 提升到 64，高并发 profile 可配置到 128。
- Redis Cluster：提供 6 节点本地 Cluster compose 和 `redis-cluster` profile。
- MySQL 分段库存：`sku_stock_segment` 把数据库扣减热点拆到多个库存段。
- 本地售罄标记：库存扣空后短 TTL 挡住无效请求，减少 Redis 空打。
- 动态限流：DB 规则 + 本地短缓存，支持活动期间临时调整阈值。
- MQ 消费限速：消费者并发、最大并发、prefetch 从硬编码改成配置项。
- 多实例示例：`docker-compose.app-scale.yml` 提供 4 个 Spring Boot 实例示例。
- Nginx 示例：`nginx/nginx-100k.conf` 提供多实例 upstream、连接数和限流配置。
- 压测脚本：`scripts/k6-100k-spike.js` 用于模拟 10 万 VU 涌入，不建议在普通笔记本直接满额跑。
- Actuator/Micrometer：暴露入口、Redis、MQ、订单和回放指标。

多实例演示启动：

```bash
mvn package
docker compose -f docker-compose.yml -f docker-compose.app-scale.yml up -d --build
```

高并发 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=100k
```

10 万 VU 涌入脚本：

```bash
k6 run -e BASE_URL=http://localhost:80 -e TARGET_VUS=100000 -e STOCK=1000 scripts/k6-100k-spike.js
```

面试口径：这个项目现在有 10 万并发方向的关键结构，也补了 Redis Cluster 本地演示。但它仍然不等于生产级每秒 10 万完整下单，生产级还需要 RabbitMQ 集群、网关集群、订单分库分表、真实监控告警和分布式压测环境。

## 11. 当前简化点

这是个人面试项目，不宣称生产级系统：

- 没有登录鉴权，直接使用 `userId`
- token 是库存倍数资格池加短 TTL 的简化版动态秒杀令牌，不是真实风控系统
- Redis Cluster 已提供本地演示版，但没有做云上多机部署和真实故障切换压测
- Redis 库存分片按用户 hash 加扫描补偿，仍可能存在少卖，需要对账修正
- RabbitMQ 状态机、退避、DEAD 和回放已接入，但没有做完整告警后台
- Redis 宕机恢复已实现入口恢复态和库存重建，但生产级还要做分布式恢复进度表和人工审核
- 10 万 VU 脚本是压测设计材料，普通单机环境不代表生产承载结果
- 对账任务已有分页扫描和补偿记录，但生产级应接入监控、告警和人工工单
- 活动状态机只做核心状态，没有复杂审批、灰度、配置发布
- `/api/admin/**` 是个人项目演示接口，没有登录鉴权和权限审计

## 12. 生产级改进方向

1. 网关层接入更完整的限流、黑名单、验证码、设备指纹。
2. Redis Cluster 进一步做多机部署、故障演练、热点 key 监控，并对超级热 key 做更细粒度库存分片。
3. MQ 增加死信告警、人工审核、操作审计和更完整的批量重放后台。
4. MySQL 做分段库存压测、读写分离、分库分表、冷热归档和慢 SQL 监控。
5. Redis 宕机恢复接入 Sentinel/Cluster 事件、恢复编排、全量分页预热、恢复进度表和人工确认开关。
6. 对账任务输出补偿记录，接入 Prometheus、Grafana、日志平台和告警。
7. 活动配置做灰度发布和状态流转审计。

## 13. 压测方案

k6 脚本：

```bash
k6 run -e BASE_URL=http://localhost:8080 -e SKU_ID=1001 -e STOCK=100 -e RATE=200 scripts/k6-seckill.js
```

验收 SQL：

```bash
mysql -uminiseckill -pminiseckill < scripts/verify.sql
```

重点看：

- 成功订单数不能超过初始化库存
- MySQL 可用库存不能小于 0
- 不存在同一活动、同一用户、同一 SKU 的重复订单
- `seckill_message` 最终应收敛到 `CONSUMED`、`FAILED` 或 `TIMEOUT`
- Redis 分片库存汇总应能和 MySQL 对账收敛
