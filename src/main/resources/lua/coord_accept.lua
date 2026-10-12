-- KEYS: version, inflight hash, deadlines zset, user owner, status
-- ARGV: requestId, userId, bucketCount, nextVersion, queuingStatus, statusTtlMs
-- The local message INSERT has returned success: clear the reservation without refund and
-- project QUEUING in one round trip. Stock keys are neither read nor written here.
local function typed(k, t) local v = redis.call('TYPE', k).ok; return v == 'none' or v == t end
if not typed(KEYS[1], 'string') or not typed(KEYS[2], 'hash') or not typed(KEYS[3], 'zset')
    or not typed(KEYS[4], 'string') or not typed(KEYS[5], 'string') then return -4 end
local code = 1
local metadata = redis.call('HGET', KEYS[2], ARGV[1])
local version = redis.call('GET', KEYS[1])
if not metadata then
    code = 0 -- another resolver already completed the settlement
elseif string.sub(metadata, 1, #ARGV[2] + 1) ~= ARGV[2] .. ':' then
    code = -3
elseif not version then
    code = -1
elseif tonumber(string.match(version, '^(%d+):')) ~= tonumber(ARGV[3]) then
    code = -4
else
    local bucket = tonumber(string.match(metadata, ':(%-?%d+)$'))
    if not bucket or bucket < -1 or bucket >= tonumber(ARGV[3]) then code = -4 end
end
if code == 1 then
    redis.call('HDEL', KEYS[2], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    redis.call('SET', KEYS[1], ARGV[4])
end
-- As coord_project: only the current owner projects, and QUEUING never overwrites a terminal
-- status. A refused settlement stays recoverable, so it does not suppress the projection.
if redis.call('GET', KEYS[4]) == ARGV[1] then
    local current = redis.call('GET', KEYS[5])
    if not current or current == '0' or current == '1' then
        redis.call('SET', KEYS[5], ARGV[5], 'PX', ARGV[6])
    end
end
return code
