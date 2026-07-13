package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.StockViewResponse;
import com.example.miniseckill.dto.TokenResponse;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.ActivityService;
import com.example.miniseckill.service.AsyncSeckillLogWriter;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.DistributedLockService;
import com.example.miniseckill.service.DynamicRateLimitService;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.service.SeckillService;
import com.example.miniseckill.service.SoldOutCacheService;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Coordinates rate limiting, Redis idempotency, Lua stock deduction, local messages, and MQ admission.
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    private final SkuStockMapper skuStockMapper;
    private final SkuStockSegmentMapper skuStockSegmentMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final SeckillMessageMapper seckillMessageMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> seckillStockScript;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final DefaultRedisScript<Long> rateLimitSlidingScript;
    private final DefaultRedisScript<Long> compareAndDeleteScript;
    private final SeckillProducer seckillProducer;
    private final OrderService orderService;
    private final ActivityService activityService;
    private final SeckillProperties seckillProperties;
    private final DistributedLockService distributedLockService;
    private final RedisRecoveryStateService redisRecoveryStateService;
    private final DynamicRateLimitService dynamicRateLimitService;
    private final SoldOutCacheService soldOutCacheService;
    private final SeckillMetrics seckillMetrics;
    private final AsyncSeckillLogWriter asyncSeckillLogWriter;

    public SeckillServiceImpl(SkuStockMapper skuStockMapper,
                              SkuStockSegmentMapper skuStockSegmentMapper,
                              SeckillLogMapper seckillLogMapper,
                              SeckillMessageMapper seckillMessageMapper,
                              SeckillOrderMapper seckillOrderMapper,
                              StringRedisTemplate stringRedisTemplate,
                              DefaultRedisScript<Long> seckillStockScript,
                              DefaultRedisScript<Long> rateLimitScript,
                              DefaultRedisScript<Long> rateLimitSlidingScript,
                              DefaultRedisScript<Long> compareAndDeleteScript,
                              SeckillProducer seckillProducer,
                              OrderService orderService,
                              ActivityService activityService,
                              SeckillProperties seckillProperties,
                              DistributedLockService distributedLockService,
                              RedisRecoveryStateService redisRecoveryStateService,
                              DynamicRateLimitService dynamicRateLimitService,
                              SoldOutCacheService soldOutCacheService,
                              SeckillMetrics seckillMetrics,
                              AsyncSeckillLogWriter asyncSeckillLogWriter) {
        this.skuStockMapper = skuStockMapper;
        this.skuStockSegmentMapper = skuStockSegmentMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.seckillMessageMapper = seckillMessageMapper;
        this.seckillOrderMapper = seckillOrderMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillStockScript = seckillStockScript;
        this.rateLimitScript = rateLimitScript;
        this.rateLimitSlidingScript = rateLimitSlidingScript;
        this.compareAndDeleteScript = compareAndDeleteScript;
        this.seckillProducer = seckillProducer;
        this.orderService = orderService;
        this.activityService = activityService;
        this.seckillProperties = seckillProperties;
        this.distributedLockService = distributedLockService;
        this.redisRecoveryStateService = redisRecoveryStateService;
        this.dynamicRateLimitService = dynamicRateLimitService;
        this.soldOutCacheService = soldOutCacheService;
        this.seckillMetrics = seckillMetrics;
        this.asyncSeckillLogWriter = asyncSeckillLogWriter;
    }

    @Override
    public Result<Void> initStock(Long activityId, Long skuId, Integer stock) {
        validateActivitySku(activityId, skuId);
        if (stock == null || stock < 0) {
            throw new BusinessException(400, "stock 参数不合法");
        }

        return distributedLockService.executeWithLock(
                RedisKeyUtil.stockInitLockKey(activityId, skuId),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                () -> {
                    activityService.assertExists(activityId);
                    skuStockMapper.upsertStock(activityId, skuId, stock);
                    initMysqlStockSegments(activityId, skuId, stock);
                    writeRedisStock(activityId, skuId, stock);
                    writeTokenQuota(activityId, skuId, stock);
                    safeLog(UUID.randomUUID().toString(), activityId, 0L, skuId, "INIT_STOCK_" + stock);
                    return Result.success("初始化成功", null);
                }
        );
    }

    @Override
    public Result<Void> warmupStock(Long activityId, Long skuId) {
        validateActivitySku(activityId, skuId);
        return distributedLockService.executeWithLock(
                RedisKeyUtil.stockInitLockKey(activityId, skuId),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                () -> {
                    activityService.assertExists(activityId);
                    SkuStock stock = skuStockMapper.selectBySkuId(activityId, skuId);
                    if (stock == null) {
                        throw new BusinessException(404, "MySQL 库存不存在，无法预热");
                    }
                    int availableStock = mysqlAvailableStock(stock);
                    writeRedisStock(activityId, skuId, availableStock);
                    writeTokenQuota(activityId, skuId, availableStock);
                    safeLog(UUID.randomUUID().toString(), activityId, 0L, skuId, "WARMUP_STOCK_" + availableStock);
                    return Result.success("预热成功", null);
                }
        );
    }

    @Override
    public Result<TokenResponse> createOrderToken(Long activityId, Long userId, Long skuId, String clientIp) {
        validateActivitySku(activityId, skuId);
        if (userId == null || userId <= 0) {
            throw new BusinessException(400, "userId 参数不合法");
        }
        assertRedisReadyForAdmission();
        activityService.assertRunning(activityId);

        if (hasAcceptedOrFinishedOrder(activityId, userId, skuId)) {
            seckillMetrics.admission("token_duplicate_or_queued");
            return Result.fail(409, "重复下单或正在排队中");
        }

        if (soldOutCacheService.isSoldOut(activityId, skuId)) {
            seckillMetrics.admission("token_sold_out_local");
            return Result.fail(1002, "库存不足");
        }

        if (!passRateLimit(activityId, userId, skuId, clientIp)) {
            safeLog(UUID.randomUUID().toString(), activityId, userId, skuId, "TOKEN_RATE_LIMITED");
            seckillMetrics.admission("token_rate_limited");
            return Result.fail(429, "请求过于频繁，请稍后再试");
        }

        Duration tokenTtl = seckillProperties.getAntiBrush().getTokenTtl();
        String tokenKey = RedisKeyUtil.tokenKey(activityId, userId, skuId);
        String existingToken = stringRedisTemplate.opsForValue().get(tokenKey);
        if (StringUtils.hasText(existingToken)) {
            String orderPath = ensureOrderPath(activityId, userId, skuId, tokenTtl);
            seckillMetrics.admission("token_reused");
            return Result.success(new TokenResponse(activityId, userId, skuId, existingToken, orderPath, tokenTtl.toSeconds()));
        }

        String quotaKey = RedisKeyUtil.tokenQuotaKey(activityId, skuId);
        boolean quotaDeducted = tryAcquireTokenQuota(activityId, skuId, quotaKey);
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(tokenKey, token, tokenTtl);
        if (!Boolean.TRUE.equals(created)) {
            if (quotaDeducted) {
                stringRedisTemplate.opsForValue().increment(quotaKey);
            }
            String currentToken = stringRedisTemplate.opsForValue().get(tokenKey);
            if (StringUtils.hasText(currentToken)) {
                String orderPath = ensureOrderPath(activityId, userId, skuId, tokenTtl);
                seckillMetrics.admission("token_reused_race");
                return Result.success(new TokenResponse(activityId, userId, skuId, currentToken, orderPath, tokenTtl.toSeconds()));
            }
            throw new BusinessException(503, "秒杀 token 创建失败，请重试");
        }
        String orderPath = ensureOrderPath(activityId, userId, skuId, tokenTtl);
        safeLog(UUID.randomUUID().toString(), activityId, userId, skuId, "TOKEN_CREATED");
        seckillMetrics.admission("token_created");
        return Result.success(new TokenResponse(activityId, userId, skuId, token, orderPath, tokenTtl.toSeconds()));
    }

    @Override
    public Result<Void> placeOrder(SeckillOrderRequest request, String clientIp) {
        Long activityId = resolveActivityId(request.getActivityId());
        Long userId = request.getUserId();
        Long skuId = request.getSkuId();
        validateActivitySku(activityId, skuId);
        assertRedisReadyForAdmission();
        activityService.assertRunning(activityId);

        if (soldOutCacheService.isSoldOut(activityId, skuId)) {
            seckillMetrics.admission("sold_out_local");
            return Result.fail(1002, "库存不足");
        }

        String requestId = UUID.randomUUID().toString();
        if (!passRateLimit(activityId, userId, skuId, clientIp)) {
            safeLog(requestId, activityId, userId, skuId, "RATE_LIMITED");
            seckillMetrics.admission("rate_limited");
            return Result.fail(429, "请求过于频繁，请稍后再试");
        }

        validateOrderToken(activityId, userId, skuId, request.getToken());

        String userSkuKey = RedisKeyUtil.userSkuKey(activityId, userId, skuId);
        String orderStatusKey = RedisKeyUtil.orderStatusKey(activityId, userId, skuId);

        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(userSkuKey, requestId, seckillProperties.getIdempotentTtl());
        if (!Boolean.TRUE.equals(locked)) {
            safeLog(requestId, activityId, userId, skuId, "DUPLICATE_REQUEST");
            seckillMetrics.admission("duplicate");
            return Result.fail(409, "重复下单或正在排队中");
        }

        StockAdmission admission = deductRedisStock(activityId, skuId, userId);
        Long stockResult = admission.result();

        if (stockResult == null || stockResult == -1L) {
            stringRedisTemplate.delete(userSkuKey);
            safeSetOrderStatus(orderStatusKey, OrderStatus.FAILED);
            safeLog(requestId, activityId, userId, skuId, "REDIS_STOCK_NOT_FOUND");
            seckillMetrics.redisStock("not_found");
            return Result.fail(404, "库存未初始化");
        }
        if (stockResult == 0L) {
            stringRedisTemplate.delete(userSkuKey);
            safeSetOrderStatus(orderStatusKey, OrderStatus.FAILED);
            soldOutCacheService.markSoldOut(activityId, skuId);
            safeLog(requestId, activityId, userId, skuId, "REDIS_STOCK_NOT_ENOUGH");
            seckillMetrics.redisStock("not_enough");
            return Result.fail(1002, "库存不足");
        }
        seckillMetrics.redisStock("deduct_success");

        SeckillMessage message = new SeckillMessage(requestId, activityId, userId, skuId, System.currentTimeMillis());
        try {
            seckillMessageMapper.insertPending(
                    requestId,
                    activityId,
                    userId,
                    skuId,
                    MessageStatus.PENDING.getCode()
            );
        } catch (RuntimeException ex) {
            compensateRedisAfterAdmissionFailure(userSkuKey, admission.deductedKey(), orderStatusKey);
            safeLog(requestId, activityId, userId, skuId, "LOCAL_MESSAGE_INSERT_FAILED");
            seckillMetrics.admission("local_message_failed");
            throw ex;
        }

        safeSetOrderStatus(orderStatusKey, OrderStatus.QUEUING);
        try {
            seckillProducer.send(message);
            safeLog(requestId, activityId, userId, skuId, "PUBLISHED_TO_MQ_WAIT_CONFIRM");
            seckillMetrics.admission("queued");
            return Result.success("排队中", null);
        } catch (AmqpException ex) {
            log.error("send seckill message failed, requestId={}", requestId, ex);
            seckillMessageMapper.markFailed(requestId, MessageStatus.FAILED.getCode(), shortError(ex));
            safeLog(requestId, activityId, userId, skuId, "MQ_SEND_FAILED_WAIT_RETRY");

            if (seckillProperties.isMqFallbackSync()) {
                try {
                    orderService.createOrderFromMessage(message);
                    seckillMessageMapper.updateStatus(requestId, MessageStatus.CONSUMED.getCode());
                    safeLog(requestId, activityId, userId, skuId, "SYNC_FALLBACK_SUCCESS");
                    seckillMetrics.admission("sync_fallback_success");
                    return Result.success("下单成功", null);
                } catch (RuntimeException syncEx) {
                    safeSetOrderStatus(orderStatusKey, OrderStatus.FAILED);
                    safeLog(requestId, activityId, userId, skuId, "SYNC_FALLBACK_FAILED");
                    seckillMetrics.admission("sync_fallback_failed");
                    throw syncEx;
                }
            }
            seckillMetrics.admission("mq_send_failed_wait_retry");
            return Result.success("排队中", null);
        }
    }

    @Override
    public Result<Void> placeOrderWithPath(String orderPath, SeckillOrderRequest request, String clientIp) {
        Long activityId = resolveActivityId(request.getActivityId());
        Long userId = request.getUserId();
        Long skuId = request.getSkuId();
        validateActivitySku(activityId, skuId);
        if (userId == null || userId <= 0) {
            throw new BusinessException(400, "userId 参数不合法");
        }
        validateOrderPath(activityId, userId, skuId, orderPath);
        return placeOrder(request, clientIp);
    }

    @Override
    public StockViewResponse queryStock(Long activityId, Long skuId) {
        validateActivitySku(activityId, skuId);

        String redisValue = stringRedisTemplate.opsForValue().get(RedisKeyUtil.stockKey(activityId, skuId));
        Integer redisStock = seckillProperties.getStockShard().isEnabled()
                ? sumBucketStock(activityId, skuId)
                : parseInteger(redisValue);
        SkuStock mysqlStock = skuStockMapper.selectBySkuId(activityId, skuId);
        if (mysqlStock == null) {
            return new StockViewResponse(activityId, skuId, redisStock, null, null, null);
        }
        int mysqlAvailableStock = mysqlAvailableStock(mysqlStock);
        int mysqlSoldCount = mysqlSoldCount(mysqlStock);
        StockViewResponse response = new StockViewResponse(
                activityId,
                skuId,
                redisStock,
                mysqlStock.getTotalStock(),
                mysqlAvailableStock,
                mysqlSoldCount
        );
        response.setShardingEnabled(seckillProperties.getStockShard().isEnabled());
        response.setBucketCount(bucketCount());
        response.setMysqlSegmentEnabled(seckillProperties.getMysqlStockSegment().isEnabled());
        response.setMysqlSegmentCount(seckillProperties.getMysqlStockSegment().getSegmentCount());
        return response;
    }

    private boolean passRateLimit(Long activityId, Long userId, Long skuId, String clientIp) {
        RateLimitPlan limit = dynamicRateLimitService.effectivePlan(activityId, skuId);
        if (!limit.enabled()) {
            return true;
        }
        return passRateLimit(RedisKeyUtil.rateSkuKey(activityId, skuId), limit.skuLimit(), limit.window())
                && passRateLimit(RedisKeyUtil.rateUserKey(activityId, userId), limit.userLimit(), limit.window())
                && passRateLimit(RedisKeyUtil.rateIpKey(activityId, clientIp == null ? "unknown" : clientIp), limit.ipLimit(), limit.window());
    }

    private boolean passRateLimit(String key, int maxCount, Duration window) {
        if (maxCount <= 0) {
            return true;
        }
        if (seckillProperties.getRateLimit().getAlgorithm() == SeckillProperties.RateLimit.Algorithm.SLIDING_WINDOW) {
            long windowMillis = Math.max(1000L, window.toMillis());
            Long result = stringRedisTemplate.execute(
                    rateLimitSlidingScript,
                    Collections.singletonList(key),
                    String.valueOf(windowMillis),
                    String.valueOf(maxCount),
                    String.valueOf(System.currentTimeMillis()),
                    UUID.randomUUID().toString()
            );
            return Long.valueOf(1L).equals(result);
        }
        long windowSeconds = Math.max(1, window.toSeconds());
        Long result = stringRedisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(windowSeconds),
                String.valueOf(maxCount)
        );
        return Long.valueOf(1L).equals(result);
    }

    private Long resolveActivityId(Long activityId) {
        return activityId == null ? seckillProperties.getDefaultActivityId() : activityId;
    }

    private boolean hasAcceptedOrFinishedOrder(Long activityId, Long userId, Long skuId) {
        // Redis-first: the token endpoint is hot, so short-circuit on Redis state before touching MySQL.
        // Most duplicate requests are caught here (queuing/success status or the idempotency key),
        // and only a genuinely fresh user falls through to the authoritative order-table lookup.
        String statusValue = stringRedisTemplate.opsForValue().get(RedisKeyUtil.orderStatusKey(activityId, userId, skuId));
        Integer status = parseInteger(statusValue);
        if (status != null && (status == OrderStatus.QUEUING.getCode() || status == OrderStatus.SUCCESS.getCode())) {
            return true;
        }
        Boolean hasQueueKey = stringRedisTemplate.hasKey(RedisKeyUtil.userSkuKey(activityId, userId, skuId));
        if (Boolean.TRUE.equals(hasQueueKey)) {
            return true;
        }
        return seckillOrderMapper.selectByUserSku(activityId, userId, skuId) != null;
    }

    private void validateActivitySku(Long activityId, Long skuId) {
        if (activityId == null || activityId <= 0 || skuId == null || skuId <= 0) {
            throw new BusinessException(400, "activityId 和 skuId 参数不合法");
        }
    }

    private void assertRedisReadyForAdmission() {
        if (redisRecoveryStateService.isRecovering()) {
            throw new BusinessException(503, "Redis 库存恢复中，秒杀入口暂时关闭");
        }
    }

    private void validateOrderToken(Long activityId, Long userId, Long skuId, String token) {
        if (!seckillProperties.getAntiBrush().isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(403, "缺少秒杀 token");
        }
        String tokenKey = RedisKeyUtil.tokenKey(activityId, userId, skuId);
        Long consumed = stringRedisTemplate.execute(compareAndDeleteScript, Collections.singletonList(tokenKey), token);
        if (!Long.valueOf(1L).equals(consumed)) {
            throw new BusinessException(403, "秒杀 token 无效或已过期");
        }
    }

    private String ensureOrderPath(Long activityId, Long userId, Long skuId, Duration ttl) {
        if (!hiddenOrderPathEnabled()) {
            return null;
        }
        String pathKey = RedisKeyUtil.orderPathKey(activityId, userId, skuId);
        String existingPath = stringRedisTemplate.opsForValue().get(pathKey);
        if (StringUtils.hasText(existingPath)) {
            return existingPath;
        }
        String orderPath = UUID.randomUUID().toString().replace("-", "");
        Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(pathKey, orderPath, ttl);
        if (Boolean.TRUE.equals(created)) {
            return orderPath;
        }
        return stringRedisTemplate.opsForValue().get(pathKey);
    }

    private void validateOrderPath(Long activityId, Long userId, Long skuId, String orderPath) {
        if (!hiddenOrderPathEnabled()) {
            return;
        }
        if (!StringUtils.hasText(orderPath)) {
            throw new BusinessException(403, "缺少秒杀路径");
        }
        String expectedPath = stringRedisTemplate.opsForValue().get(RedisKeyUtil.orderPathKey(activityId, userId, skuId));
        if (!orderPath.equals(expectedPath)) {
            throw new BusinessException(403, "秒杀路径无效或已过期");
        }
    }

    private boolean hiddenOrderPathEnabled() {
        SeckillProperties.AntiBrush antiBrush = seckillProperties.getAntiBrush();
        return antiBrush.isEnabled() && antiBrush.isHiddenPathEnabled();
    }

    private StockAdmission deductRedisStock(Long activityId, Long skuId, Long userId) {
        if (!seckillProperties.getStockShard().isEnabled()) {
            String stockKey = RedisKeyUtil.stockKey(activityId, skuId);
            Long result = stringRedisTemplate.execute(seckillStockScript, Collections.singletonList(stockKey));
            return new StockAdmission(result, Long.valueOf(1L).equals(result) ? stockKey : null);
        }

        List<String> bucketKeys = stockBucketKeys(activityId, skuId);
        int start = Math.floorMod(userId.hashCode(), bucketKeys.size());
        boolean hasAnyBucket = false;
        for (int offset = 0; offset < bucketKeys.size(); offset++) {
            String bucketKey = bucketKeys.get((start + offset) % bucketKeys.size());
            Long result = stringRedisTemplate.execute(seckillStockScript, Collections.singletonList(bucketKey));
            if (Long.valueOf(1L).equals(result)) {
                return new StockAdmission(1L, bucketKey);
            }
            if (!Long.valueOf(-1L).equals(result)) {
                hasAnyBucket = true;
            }
        }
        return new StockAdmission(hasAnyBucket ? 0L : -1L, null);
    }

    private void writeRedisStock(Long activityId, Long skuId, int stock) {
        stringRedisTemplate.opsForValue().set(RedisKeyUtil.stockKey(activityId, skuId), String.valueOf(stock));
        if (stock > 0) {
            soldOutCacheService.clear(activityId, skuId);
        } else {
            soldOutCacheService.markSoldOut(activityId, skuId);
        }
        if (!seckillProperties.getStockShard().isEnabled()) {
            return;
        }
        List<String> bucketKeys = stockBucketKeys(activityId, skuId);
        deleteKeysOneByOne(bucketKeys);
        int count = bucketKeys.size();
        int base = stock / count;
        int remainder = stock % count;
        for (int i = 0; i < count; i++) {
            int bucketStock = base + (i < remainder ? 1 : 0);
            stringRedisTemplate.opsForValue().set(bucketKeys.get(i), String.valueOf(bucketStock));
        }
    }

    private void writeTokenQuota(Long activityId, Long skuId, int stock) {
        SeckillProperties.AntiBrush antiBrush = seckillProperties.getAntiBrush();
        if (!antiBrush.isEnabled() || !antiBrush.isTokenQuotaEnabled()) {
            return;
        }
        long multiplier = Math.max(1, antiBrush.getTokenQuotaMultiplier());
        long quota = Math.max(0L, (long) stock * multiplier);
        stringRedisTemplate.opsForValue().set(RedisKeyUtil.tokenQuotaKey(activityId, skuId), String.valueOf(quota));
    }

    private boolean tryAcquireTokenQuota(Long activityId, Long skuId, String quotaKey) {
        SeckillProperties.AntiBrush antiBrush = seckillProperties.getAntiBrush();
        if (!antiBrush.isEnabled() || !antiBrush.isTokenQuotaEnabled()) {
            return false;
        }
        Long result = stringRedisTemplate.execute(seckillStockScript, Collections.singletonList(quotaKey));
        if (Long.valueOf(1L).equals(result)) {
            return true;
        }
        if (Long.valueOf(-1L).equals(result)) {
            safeLog(UUID.randomUUID().toString(), activityId, 0L, skuId, "TOKEN_QUOTA_NOT_FOUND");
            throw new BusinessException(404, "秒杀资格池未初始化，请先初始化或预热库存");
        }
        safeLog(UUID.randomUUID().toString(), activityId, 0L, skuId, "TOKEN_QUOTA_EMPTY");
        throw new BusinessException(429, "秒杀资格已发完，请稍后再试");
    }

    private Integer sumBucketStock(Long activityId, Long skuId) {
        int sum = 0;
        boolean found = false;
        for (String bucketKey : stockBucketKeys(activityId, skuId)) {
            Integer bucketStock = parseInteger(stringRedisTemplate.opsForValue().get(bucketKey));
            if (bucketStock != null) {
                found = true;
                sum += bucketStock;
            }
        }
        return found ? sum : null;
    }

    private List<String> stockBucketKeys(Long activityId, Long skuId) {
        int count = bucketCount();
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(RedisKeyUtil.stockBucketKey(activityId, skuId, i));
        }
        return keys;
    }

    private int bucketCount() {
        return Math.max(1, seckillProperties.getStockShard().getBucketCount());
    }

    private Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void compensateRedisAfterAdmissionFailure(String userSkuKey, String stockKey, String orderStatusKey) {
        stringRedisTemplate.delete(userSkuKey);
        stringRedisTemplate.delete(orderStatusKey);
        if (stockKey != null) {
            stringRedisTemplate.opsForValue().increment(stockKey);
        }
    }

    private void initMysqlStockSegments(Long activityId, Long skuId, int stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()) {
            return;
        }
        int segmentCount = Math.max(1, seckillProperties.getMysqlStockSegment().getSegmentCount());
        skuStockSegmentMapper.deleteByActivitySku(activityId, skuId);
        int base = stock / segmentCount;
        int remainder = stock % segmentCount;
        for (int i = 0; i < segmentCount; i++) {
            int segmentStock = base + (i < remainder ? 1 : 0);
            skuStockSegmentMapper.upsertSegment(activityId, skuId, i, segmentStock);
        }
        skuStockMapper.syncFromSegments(activityId, skuId);
    }

    private int mysqlAvailableStock(SkuStock stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()
                || skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) == 0) {
            return stock.getAvailableStock();
        }
        return skuStockSegmentMapper.sumAvailableStock(stock.getActivityId(), stock.getSkuId());
    }

    private int mysqlSoldCount(SkuStock stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()
                || skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) == 0) {
            return stock.getSoldCount();
        }
        return skuStockSegmentMapper.sumSoldCount(stock.getActivityId(), stock.getSkuId());
    }

    private void deleteKeysOneByOne(List<String> keys) {
        for (String key : keys) {
            stringRedisTemplate.delete(key);
        }
    }

    private void safeSetOrderStatus(String key, OrderStatus status) {
        try {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(status.getCode()), seckillProperties.getOrderStatusTtl());
        } catch (RuntimeException ex) {
            log.warn("set order status failed, key={}, status={}", key, status, ex);
        }
    }

    private void safeLog(String requestId, Long activityId, Long userId, Long skuId, String result) {
        // Off-path async write: the hot order/token endpoints emit several audit rows per request,
        // and synchronous inserts made MySQL part of every request's latency. Never throws.
        asyncSeckillLogWriter.write(requestId, activityId, userId, skuId, result);
    }

    private String shortError(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record StockAdmission(Long result, String deductedKey) {
    }
}
