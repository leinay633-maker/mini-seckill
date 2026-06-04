USE mini_seckill;

SET @activity_id = 1;
SET @sku_id = 1001;

SELECT
    activity_id,
    sku_id,
    total_stock,
    available_stock,
    sold_count
FROM sku_stock
WHERE activity_id = @activity_id
  AND sku_id = @sku_id;

SELECT
    activity_id,
    sku_id,
    COUNT(*) AS segment_count,
    SUM(total_stock) AS segment_total_stock,
    SUM(available_stock) AS segment_available_stock,
    SUM(sold_count) AS segment_sold_count
FROM sku_stock_segment
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
GROUP BY activity_id, sku_id;

SELECT
    COUNT(*) AS success_order_count
FROM seckill_order
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
  AND status = 2;

SELECT
    activity_id,
    user_id,
    sku_id,
    COUNT(*) AS duplicate_count
FROM seckill_order
GROUP BY activity_id, user_id, sku_id
HAVING COUNT(*) > 1;

SELECT
    status,
    retry_count,
    COUNT(*) AS message_count
FROM seckill_message
GROUP BY status, retry_count
ORDER BY status, retry_count;

SELECT
    type,
    status,
    COUNT(*) AS compensation_count
FROM seckill_compensation
GROUP BY type, status
ORDER BY type, status;

SELECT
    activity_id,
    name,
    status,
    start_time,
    end_time
FROM seckill_activity
WHERE activity_id = @activity_id;
