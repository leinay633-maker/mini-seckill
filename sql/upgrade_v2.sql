USE mini_seckill;

ALTER TABLE sku_stock
  ADD COLUMN activity_id BIGINT NOT NULL DEFAULT 1 AFTER id;

ALTER TABLE sku_stock
  DROP INDEX uk_sku_id,
  ADD UNIQUE KEY uk_activity_sku (activity_id, sku_id);

ALTER TABLE seckill_order
  ADD COLUMN activity_id BIGINT NOT NULL DEFAULT 1 AFTER order_id;

ALTER TABLE seckill_order
  DROP INDEX uk_user_sku,
  ADD UNIQUE KEY uk_activity_user_sku (activity_id, user_id, sku_id),
  ADD KEY idx_activity_sku (activity_id, sku_id);

ALTER TABLE seckill_log
  ADD COLUMN activity_id BIGINT NOT NULL DEFAULT 1 AFTER request_id,
  DROP INDEX idx_user_sku,
  ADD KEY idx_activity_user_sku (activity_id, user_id, sku_id);

CREATE TABLE IF NOT EXISTS seckill_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  status TINYINT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_status_retry (status, retry_count, updated_at),
  KEY idx_activity_sku (activity_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
