USE mini_seckill;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE seckill_order;
TRUNCATE TABLE seckill_message;
TRUNCATE TABLE seckill_log;
TRUNCATE TABLE seckill_compensation;
TRUNCATE TABLE seckill_rate_limit_rule;
TRUNCATE TABLE sku_stock_segment;
TRUNCATE TABLE sku_stock;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO seckill_activity (activity_id, name, status, start_time, end_time, created_at, updated_at)
VALUES (1, 'default-seckill-activity', 1, '2020-01-01 00:00:00', '2099-12-31 23:59:59', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = 1,
  start_time = VALUES(start_time),
  end_time = VALUES(end_time),
  updated_at = NOW();
