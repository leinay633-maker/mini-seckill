CREATE DATABASE IF NOT EXISTS mini_seckill
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE mini_seckill;

CREATE TABLE IF NOT EXISTS seckill_activity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  status TINYINT NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_activity_id (activity_id),
  KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sku_stock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL DEFAULT 1,
  sku_id BIGINT NOT NULL,
  total_stock INT NOT NULL,
  available_stock INT NOT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_activity_sku (activity_id, sku_id),
  KEY idx_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE IF NOT EXISTS seckill_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL DEFAULT 1,
  user_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  status TINYINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_order_id (order_id),
  UNIQUE KEY uk_activity_user_sku (activity_id, user_id, sku_id),
  KEY idx_activity_sku (activity_id, sku_id),
  KEY idx_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS seckill_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  activity_id BIGINT NOT NULL DEFAULT 1,
  user_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  result VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_activity_user_sku (activity_id, user_id, sku_id),
  KEY idx_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS seckill_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  status TINYINT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512) DEFAULT NULL,
  next_retry_at DATETIME DEFAULT NULL,
  dead_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_status_retry (status, retry_count, next_retry_at, updated_at),
  KEY idx_activity_sku (activity_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

INSERT INTO seckill_activity (activity_id, name, status, start_time, end_time, created_at, updated_at)
VALUES (1, 'default-seckill-activity', 1, '2020-01-01 00:00:00', '2099-12-31 23:59:59', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  updated_at = NOW();
