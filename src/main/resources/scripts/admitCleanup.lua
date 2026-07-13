-- KEYS[1] = eventKey
-- ARGV[1] = admitRate

-- KEYS[2] = "queue:active-events"
-- ARGV[2] = eventId

local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
local remaining = redis.call('ZCARD', KEYS[1])
if remaining == 0 then
    redis.call('SREM', KEYS[2], ARGV[2])
end
return popped