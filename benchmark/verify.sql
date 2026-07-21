USE mini_seckill;

SET @activity_id = 1;
SET @sku_id = 1001;
SET @success_status = 2;
SET @consumed_status = 2;

SELECT 'stock_summary' AS section,
       activity_id,
       sku_id,
       total_stock,
       available_stock,
       sold_count
FROM sku_stock
WHERE activity_id = @activity_id
  AND sku_id = @sku_id;

SELECT 'stock_segments' AS section,
       activity_id,
       sku_id,
       COUNT(*) AS segment_count,
       COALESCE(SUM(total_stock), 0) AS segment_total_stock,
       COALESCE(SUM(available_stock), 0) AS segment_available_stock,
       COALESCE(SUM(sold_count), 0) AS segment_sold_count,
       MIN(available_stock) AS min_segment_available
FROM sku_stock_segment
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
GROUP BY activity_id, sku_id;

SELECT 'orders' AS section,
       COUNT(*) AS total_orders,
       SUM(status = @success_status) AS success_orders,
       COUNT(DISTINCT user_id) AS distinct_order_users,
       MIN(created_at) AS first_order_at,
       MAX(created_at) AS last_order_at,
       TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1000 AS mysql_order_span_ms
FROM seckill_order
WHERE activity_id = @activity_id
  AND sku_id = @sku_id;

SELECT 'duplicate_order_groups' AS section,
       COUNT(*) AS duplicate_group_count
FROM (
  SELECT activity_id, user_id, sku_id
  FROM seckill_order
  WHERE activity_id = @activity_id
    AND sku_id = @sku_id
  GROUP BY activity_id, user_id, sku_id
  HAVING COUNT(*) > 1
) t;

SELECT 'order_id_uniqueness' AS section,
       COUNT(*) AS order_count,
       COUNT(DISTINCT order_id) AS distinct_order_id_count,
       COUNT(*) - COUNT(DISTINCT order_id) AS duplicate_order_id_count
FROM seckill_order
WHERE activity_id = @activity_id
  AND sku_id = @sku_id;

SELECT 'order_worker_distribution' AS section,
       ((order_id >> 12) & 1023) AS worker_id,
       COUNT(*) AS order_count
FROM seckill_order
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
GROUP BY ((order_id >> 12) & 1023)
ORDER BY worker_id;

SELECT 'messages_by_status' AS section,
       status,
       retry_count,
       COUNT(*) AS message_count
FROM seckill_message
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
GROUP BY status, retry_count
ORDER BY status, retry_count;

SELECT 'message_order_consistency' AS section,
       (SELECT COUNT(*) FROM seckill_message WHERE activity_id = @activity_id AND sku_id = @sku_id) AS local_messages,
       (SELECT COUNT(*) FROM seckill_message WHERE activity_id = @activity_id AND sku_id = @sku_id AND status = @consumed_status) AS consumed_messages,
       (SELECT COUNT(*) FROM seckill_order WHERE activity_id = @activity_id AND sku_id = @sku_id AND status = @success_status) AS success_orders,
       (
         (SELECT COUNT(*) FROM seckill_message WHERE activity_id = @activity_id AND sku_id = @sku_id AND status = @consumed_status)
         =
         (SELECT COUNT(*) FROM seckill_order WHERE activity_id = @activity_id AND sku_id = @sku_id AND status = @success_status)
       ) AS consumed_equals_orders;

SELECT 'compensations' AS section,
       type,
       status,
       COUNT(*) AS compensation_count
FROM seckill_compensation
WHERE activity_id = @activity_id
  AND sku_id = @sku_id
GROUP BY type, status
ORDER BY type, status;
