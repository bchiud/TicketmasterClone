-- KEYS[1] = "queue:active-events"
-- ARGV[1] = eventId

-- KEYS[2] = eventKey + ":seq"

-- KEYS[3] = eventKey
-- ARGV[2] = token

redis.call('SADD', KEYS[1], ARGV[1])
local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[3], seq, ARGV[2])
return seq