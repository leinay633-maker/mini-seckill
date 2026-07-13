-- Sliding-window rate limiter backed by a sorted set.
-- KEYS[1]  = rate limit key
-- ARGV[1]  = window length in milliseconds
-- ARGV[2]  = max requests allowed within the window
-- ARGV[3]  = current time in millis, passed in by the caller (the app instance clock, kept injectable
--            so tests can pin it). NOTE: with multiple app instances this is each instance's own clock;
--            clock skew across instances can make the shared ZSET window slightly imprecise. For a true
--            distributed limiter use redis.call('TIME') here to read Redis's single clock instead.
-- ARGV[4]  = unique member for this request (e.g. requestId) so same-millisecond calls don't collide
-- Returns 1 if allowed, 0 if rejected.
--
-- Unlike the fixed-window counter (rate_limit.lua), this cannot be doubled at a window boundary:
-- the window always spans the last ARGV[1] ms relative to now, not a fixed calendar bucket.

local window = tonumber(ARGV[1])
local maxCount = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local member = ARGV[4]

-- Drop entries that fell out of the trailing window.
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

local current = redis.call('ZCARD', KEYS[1])
if current >= maxCount then
    return 0
end

redis.call('ZADD', KEYS[1], now, member)
-- TTL = exactly the window length. Refreshed on every add, so the key never expires before its
-- newest entry leaves the window (entry leaves at score+window == this add's now+window == key expiry),
-- i.e. no count is lost; idle keys still expire and don't leak.
redis.call('PEXPIRE', KEYS[1], window)
return 1
