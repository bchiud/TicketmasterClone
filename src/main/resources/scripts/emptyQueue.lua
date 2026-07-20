-- KEYS[1] = eventKey (queue zset)

-- KEYS[2] = admittedCountKey
-- ARGV[2] = admitIntervalMs (counter window)

-- KEYS[3] = accessKey
-- ARGV[1] = admitRate
-- ARGV[3] = accessTtlMs

-- escape hatch for empty queue
if redis.call('ZCARD', KEYS[1]) ~= 0 then
    return 0
end

local count = redis.call('INCR', KEYS[2])
-- window self-clears every admitIntervalMs so the escape hatch reopens once traffic calms down
if count == 1 then
    redis.call('PEXPIRE', KEYS[2], ARGV[2])
end

-- grant access
if count <= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[3], '1', 'PX', ARGV[3])
    return 1
end

return 0