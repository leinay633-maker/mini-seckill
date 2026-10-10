-- KEYS: total, version, inflight hash, deadlines zset, lease, user owner, status, buckets...
-- ARGV: requestId, expectedMetadata, refund(0/1), nextVersion, failedStatus, statusTtlMs
local function kind(k) return redis.call('TYPE', k).ok end
local function typed(k, t) local v = kind(k); return v == 'none' or v == t end
if not typed(KEYS[1], 'string') or not typed(KEYS[2], 'string') or not typed(KEYS[3], 'hash')
    or not typed(KEYS[4], 'zset') or not typed(KEYS[6], 'string') or not typed(KEYS[7], 'string') then return -4 end
if redis.call('EXISTS', KEYS[2]) == 0 then return -1 end
if tonumber(string.match(redis.call('GET', KEYS[2]), '^(%d+):')) ~= #KEYS-7 then return -4 end
local metadata = redis.call('HGET', KEYS[3], ARGV[1])
if not metadata then return 0 end
if metadata ~= ARGV[2] then return -3 end
local bucket = tonumber(string.match(metadata, ':(%-?%d+)$'))
if not bucket or bucket < -1 or bucket >= #KEYS-7 then return -4 end
local bucketKey = bucket >= 0 and KEYS[8+bucket] or nil
if bucketKey and not typed(bucketKey, 'string') then return -4 end
if ARGV[3] == '1' then
    -- Missing/corrupt stock is NOT recreated with INCR. The next guarded repair
    -- rebuilds it from MySQL after the cancellation fence clears this reservation.
    local stock = tonumber(redis.call('GET', KEYS[1]))
    local part = bucketKey and tonumber(redis.call('GET', bucketKey)) or 0
    if stock and stock >= 0 and stock < 2147483647 and stock % 1 == 0 and part and part >= 0 and part < 2147483647 and part % 1 == 0 then
        redis.call('INCR', KEYS[1])
        if bucketKey then redis.call('INCR', bucketKey) end
    end
    if redis.call('GET', KEYS[6]) == ARGV[1] then
        redis.call('DEL', KEYS[6])
        redis.call('SET', KEYS[7], ARGV[5], 'PX', ARGV[6])
    end
end
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('SET', KEYS[2], ARGV[4])
return 1
