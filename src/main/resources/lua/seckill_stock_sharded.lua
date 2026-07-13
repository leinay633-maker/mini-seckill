-- Sharded stock deduction in a single round trip.
-- KEYS     = all bucket keys for one sku (bucket 0..N-1, in order)
-- ARGV[1]  = start index (userId hash % N) — begin scanning here for load spread
-- Returns:
--   >= 0  : index of the bucket that was decremented (deduction succeeded)
--   -1    : buckets exist but all are empty (sold out)
--   -2    : no bucket exists (stock not initialized)
--
-- Replaces the Java-side loop that issued up to N separate DECR round trips near sell-out
-- (O(N) RTT). Here the whole scan runs inside one Lua call (O(1) RTT). Single-Redis only;
-- under Redis Cluster the bucket keys span slots, so the caller keeps the per-key fallback.

local n = #KEYS
local start = tonumber(ARGV[1])
local anyBucket = false

for offset = 0, n - 1 do
    local idx = ((start + offset) % n) + 1  -- Lua arrays are 1-based
    local stock = redis.call('GET', KEYS[idx])
    if stock then
        anyBucket = true
        if tonumber(stock) > 0 then
            redis.call('DECR', KEYS[idx])
            return idx - 1  -- return 0-based bucket index
        end
    end
end

if anyBucket then
    return -1
end
return -2
