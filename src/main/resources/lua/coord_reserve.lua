-- KEYS: total, version, inflight hash, deadlines zset, lease, user owner, buckets...
-- ARGV: requestId, userId, timeoutMs, nextVersion, startBucket
local function kind(k) return redis.call('TYPE', k).ok end
local function typed(k, t) local v = kind(k); return v == 'none' or v == t end
if not typed(KEYS[1], 'string') or not typed(KEYS[2], 'string')
    or not typed(KEYS[3], 'hash') or not typed(KEYS[4], 'zset')
    or not typed(KEYS[6], 'string') then return -4 end
if redis.call('GET', KEYS[6]) ~= ARGV[1] then return -3 end
if redis.call('EXISTS', KEYS[2]) == 0 then return -1 end
if tonumber(string.match(redis.call('GET', KEYS[2]), '^(%d+):')) ~= #KEYS-6 then return -4 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return 1 end
local total = tonumber(redis.call('GET', KEYS[1]))
if not total or total < 0 or total > 2147483647 or total % 1 ~= 0 then return -1 end
if total == 0 then return 0 end
local n = #KEYS - 6
local bucket = -1
-- Validate ALL buckets before any write: Redis Lua runtime errors do not roll back.
local stocks = {}
for i = 0, n - 1 do
    if not typed(KEYS[7+i], 'string') then return -4 end
    local value = tonumber(redis.call('GET', KEYS[7+i]))
    if not value or value < 0 or value > 2147483647 or value % 1 ~= 0 then return -1 end
    stocks[i] = value
end
if n > 0 then
    for offset = 0, n - 1 do
        local index = (tonumber(ARGV[5]) + offset) % n
        if stocks[index] > 0 then bucket = index; break end
    end
    if bucket == -1 then return -1 end
end
local time = redis.call('TIME')
local now = tonumber(time[1])*1000 + math.floor(tonumber(time[2])/1000)
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2] .. ':' .. tostring(bucket))
redis.call('ZADD', KEYS[4], now + tonumber(ARGV[3]), ARGV[1])
redis.call('DECR', KEYS[1])
if bucket >= 0 then redis.call('DECR', KEYS[7+bucket]) end
redis.call('SET', KEYS[2], ARGV[4])
return 1
