-- A delayed job may project a failure/release only while it still owns this business-key admission.
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
-- QUEUING must not overwrite a faster terminal projection for the same owner.
if ARGV[2] == '1' then
    local current = redis.call('GET', KEYS[2])
    if current and current ~= '0' and current ~= '1' then return 0 end
end
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
if ARGV[4] == '1' then redis.call('DEL', KEYS[1]) end
return 1
