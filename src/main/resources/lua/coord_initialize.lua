-- Only called AFTER an INSERT-only, transactional creation of a previously absent DB SKU.
-- Never used for warmup, ordinary reconciliation or recovery of lost metadata.
-- KEYS: total, version, inflight, deadlines, lease, buckets...; ARGV: stock, version
local function kind(k) return redis.call('TYPE', k).ok end
if redis.call('EXISTS', KEYS[2]) ~= 0 or redis.call('EXISTS', KEYS[3]) ~= 0
    or redis.call('EXISTS', KEYS[4]) ~= 0 then return 0 end
for i = 1, #KEYS do
    if i == 1 or i >= 6 then
        local t = kind(KEYS[i]); if t ~= 'none' and t ~= 'string' then return -1 end
    end
end
local stock, n = tonumber(ARGV[1]), #KEYS-5
if not stock or stock < 0 or stock > 2147483647 or stock % 1 ~= 0 then return -1 end
redis.call('SET', KEYS[1], ARGV[1])
if n > 0 then
    local base, rem = math.floor(stock/n), stock % n
    for i = 0, n-1 do redis.call('SET', KEYS[6+i], base + (i < rem and 1 or 0)) end
end
redis.call('SET', KEYS[2], ARGV[2])
return 1
