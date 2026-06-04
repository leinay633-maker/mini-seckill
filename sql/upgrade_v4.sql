USE mini_seckill;

CREATE TABLE IF NOT EXISTS sku_stock_segment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  segment_id INT NOT NULL,
  total_stock INT NOT NULL,
  available_stock INT NOT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_activity_sku_segment (activity_id, sku_id, segment_id),
  KEY idx_activity_sku (activity_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @add_next_retry_at = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE seckill_message ADD COLUMN next_retry_at DATETIME DEFAULT NULL',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'seckill_message'
    AND COLUMN_NAME = 'next_retry_at'
);
PREPARE stmt FROM @add_next_retry_at;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_dead_at = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE seckill_message ADD COLUMN dead_at DATETIME DEFAULT NULL',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'seckill_message'
    AND COLUMN_NAME = 'dead_at'
);
PREPARE stmt FROM @add_dead_at;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS seckill_rate_limit_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  window_seconds INT NOT NULL DEFAULT 1,
  sku_limit INT NOT NULL,
  user_limit INT NOT NULL,
  ip_limit INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_activity_sku (activity_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS seckill_compensation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) DEFAULT NULL,
  activity_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  detail VARCHAR(1024) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_activity_sku (activity_id, sku_id),
  KEY idx_request_id (request_id),
  KEY idx_type_status (type, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
