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

INSERT INTO seckill_activity (activity_id, name, status, start_time, end_time, created_at, updated_at)
VALUES (1, 'default-seckill-activity', 1, '2020-01-01 00:00:00', '2099-12-31 23:59:59', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  updated_at = NOW();
