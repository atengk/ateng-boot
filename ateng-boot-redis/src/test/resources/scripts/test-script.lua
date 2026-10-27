local key = KEYS[1]
local increment = tonumber(ARGV[1])
local current = redis.call('get', key)
if not current then
    redis.call('set', key, increment)
    return increment
else
    return redis.call('incrby', key, increment)
end
