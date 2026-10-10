-- KEYS: total, version, inflight, deadlines, lease, buckets...
-- ARGV: owner, expectedVersion, expectedStock, nextVersion
local function kind(k) return redis.call('TYPE', k).ok end
local function typed(k, t) local v = kind(k); return v == 'none' or v == t end
if not typed(KEYS[5], 'string') or redis.call('GET', KEYS[5]) ~= ARGV[1] then return 'LEASE_LOST' end
if not typed(KEYS[2], 'string') or redis.call('GET', KEYS[2]) ~= ARGV[2] then return 'VERSION_CHANGED' end
if not typed(KEYS[3], 'hash') or redis.call('HLEN', KEYS[3]) ~= 0 then return 'INFLIGHT' end
if tonumber(string.match(ARGV[2], '^(%d+):')) ~= #KEYS-5 then return 'LAYOUT_MISMATCH' end
local expected = tonumber(ARGV[3])
if not expected or expected < 0 or expected > 2147483647 or expected % 1 ~= 0 then return 'INVALID_STOCK' end
if not typed(KEYS[1], 'string') then return 'CORRUPT_TYPE' end
local n, sum, complete = #KEYS-5, 0, true
for i = 6, #KEYS do
    if not typed(KEYS[i], 'string') then return 'CORRUPT_TYPE' end
    local value = tonumber(redis.call('GET', KEYS[i]))
    if not value or value < 0 or value % 1 ~= 0 then complete = false else sum = sum+value end
end
if tonumber(redis.call('GET', KEYS[1])) == expected and (n == 0 or (complete and sum == expected)) then
    return 'UNCHANGED'
end
-- Every precondition is checked at the resource, in the SAME atomic script as all SETs.
redis.call('SET', KEYS[1], ARGV[3])
if n > 0 then
    local base, rem = math.floor(expected/n), expected % n
    for i = 0, n-1 do redis.call('SET', KEYS[6+i], base + (i < rem and 1 or 0)) end
end
redis.call('SET', KEYS[2], ARGV[4])
return 'APPLIED'
