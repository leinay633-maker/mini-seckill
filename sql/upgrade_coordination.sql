-- Apply exactly once, with all old JVMs stopped. init.sql is for NEW databases only.
-- The native suite checks information_schema and applies this before starting any JVM.
ALTER TABLE seckill_message
  ADD COLUMN send_token VARCHAR(64) DEFAULT NULL,
  ADD COLUMN send_lease_until DATETIME(6) DEFAULT NULL;
-- Persisted MessageStatus 11 is an admission cancellation tombstone, not a business order.
-- Never delete these tombstones while an old request/process could still resume.
