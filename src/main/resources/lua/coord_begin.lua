-- KEYS: total, version, inflight, deadlines, lease, buckets...; ARGV: owner
if redis.call('GET', KEYS[5]) ~= ARGV[1] then return {'LEASE_LOST'} end
local version = redis.call('GET', KEYS[2])
if not version then return {'UNINITIALIZED'} end
if tonumber(string.match(version, '^(%d+):')) ~= #KEYS-5 then return {'LAYOUT_MISMATCH'} end
if redis.call('HLEN', KEYS[3]) ~= 0 then return {'INFLIGHT'} end
return {'READY', version}
