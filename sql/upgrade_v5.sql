USE mini_seckill;

-- v5: remove the unused sku_stock.version column.
-- The column was only ever self-incremented (version = version + 1) but never used as an optimistic
-- lock (no WHERE version = ? guard). Concurrency safety already comes from the conditional update
-- `UPDATE ... SET available_stock = available_stock - 1 WHERE available_stock > 0`, which is an
-- atomic row-level compare-and-set. The version column was dead weight; dropping it here.
-- Idempotent: only drops if the column still exists.

SET @drop_version = (
  SELECT IF(
    COUNT(*) = 0,
    'SELECT 1',
    'ALTER TABLE sku_stock DROP COLUMN version'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sku_stock'
    AND COLUMN_NAME = 'version'
);
PREPARE stmt FROM @drop_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
