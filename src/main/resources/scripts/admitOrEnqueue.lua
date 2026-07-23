-- admit-or-enqueue: one atomic decision per arrival
-- either fast-tracks the caller straight to "admitted" (empty-queue escape hatch),
-- or places them at the back of the waiting queue
--
-- KEYS[1] = eventKey               -- the waiting line (sorted set of tokens, scored by arrival seq)
-- KEYS[2] = admittedCountKey       -- how many we've waved in this window
-- KEYS[3] = accessKey              -- per-token "you're in" flag (plain string w/ ttl)
-- KEYS[4] = "queue:active-events"  -- events that currently have people waiting
-- KEYS[5] = eventKey + ":seq"      -- counter that hands out spots in line
--
-- ARGV[1] = admitRate          -- how many get fast track wave-ins via escape hatch per window
-- ARGV[2] = admitIntervalMs    -- window length
-- ARGV[3] = accessTtlMs        -- how long an "you're in" flag lasts
-- ARGV[4] = eventId
-- ARGV[5] = token
--
-- returns 1 = in, 0 = queued

-- enter escape hatch if no one in queue
if redis.call('ZCARD', KEYS[1]) == 0 then

    -- increment fast track counter
    local count = redis.call('INCR', KEYS[2])

    -- first one this window? start expiry clock
    -- counter self-clears so the door reopens when things calm down
    if count == 1 then
        redis.call('PEXPIRE', KEYS[2], ARGV[2])
    end

    -- still within this window's fast-track budget → grant access
    if count <= tonumber(ARGV[1]) then
        redis.call('SET', KEYS[3], '1', 'PX', ARGV[3])
        return 1
    end

    -- otherwise budget is used up; fall through and get in line
end

-- get in line: either someone was already waiting, or we blew the cap above

-- flag event as having people in queue, so admit() knows to service it
redis.call('SADD', KEYS[4], ARGV[4])

-- grab our spot (1, 2, 3, ...)
local seq = redis.call('INCR', KEYS[5])

-- drop our token in the line, scored by that spot so it stays fifo
redis.call('ZADD', KEYS[1], seq, ARGV[5])

return 0
