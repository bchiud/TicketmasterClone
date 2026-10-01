-- KEYS[1] = eventKey
-- ARGV[1] = admitRate

-- KEYS[2] = "queue:active-events"
-- ARGV[2] = eventId

-- ARGV[3] = accessKeyPrefix
-- ARGV[4] = access-key TTL in ms (queue.grant-access-window-minutes)

local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
-- popped[i] = token, popped[i+1] = its sequence number
for i = 1, #popped, 2 do
    redis.call('SET', ARGV[3] .. popped[i], '1', 'PX', ARGV[4])
end
local remaining = redis.call('ZCARD', KEYS[1])
if remaining == 0 then
    redis.call('SREM', KEYS[2], ARGV[2])
end
return #popped / 2