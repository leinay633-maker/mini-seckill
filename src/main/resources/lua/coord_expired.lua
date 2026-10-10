local now = redis.call('TIME')
return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', tonumber(now[1])*1000 + math.floor(tonumber(now[2])/1000),
    'LIMIT', 0, ARGV[1])
