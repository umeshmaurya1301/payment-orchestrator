-- Token bucket, evaluated atomically inside Redis.
--
-- KEYS[1] = bucket key
-- ARGV[1] = capacity      (burst size, tokens)
-- ARGV[2] = refillPerSec  (sustained rate, tokens/second)
-- ARGV[3] = permits       (cost of this request)
-- ARGV[4] = ttlSeconds    (how long an idle bucket survives)
--
-- returns { allowed(0|1), tokensLeft, retryAfterMs }
--
-- WHY THIS IS A SCRIPT AND NOT THREE COMMANDS
--
-- The obvious implementation is GET the bucket, decide in Java, SET it back.
-- That is three network round trips with no lock between them, so N concurrent
-- callers all read the same token count, all decide there is room, and all
-- write. The limit is then enforced once per *batch of concurrent arrivals*
-- rather than once per request, and the over-admission grows with concurrency -
-- which is to say it fails hardest at exactly the load it exists to control.
-- docs/experiments/05-rate-limiters.md measures it; the effect is not subtle.
--
-- Redis runs scripts single-threaded to completion, so everything below is one
-- indivisible read-decide-write. That is the entire reason for the Lua.
--
-- WHY THE CLOCK COMES FROM REDIS
--
-- The refill needs "how long since this bucket was last touched". Taking that
-- from the calling JVM makes the answer depend on which instance called: two
-- edges whose clocks differ by 200 ms compute different refills for the same
-- bucket, and a caller whose clock runs fast can mint tokens by asking. TIME is
-- read from the single process that owns the data, so there is one clock and
-- skew cannot be exploited.
--
-- Since Redis 5 scripts replicate by effects rather than by re-execution, so a
-- non-deterministic read here is safe for replicas and for the AOF.

local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])
local permits      = tonumber(ARGV[3])
local ttlSeconds   = tonumber(ARGV[4])

local time = redis.call('TIME')
local nowMs = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil or ts == nil then
    -- A new or expired bucket starts full. Starting it empty would make every
    -- first request of an idle merchant a 429, and idle is the normal state of
    -- most merchants most of the time.
    tokens = capacity
    ts = nowMs
end

-- Refill by elapsed time rather than on a timer. No background job, no drift,
-- and a bucket nobody touches costs nothing at all: the arithmetic happens on
-- read, so an idle merchant is not work.
local elapsedMs = nowMs - ts
if elapsedMs > 0 then
    tokens = math.min(capacity, tokens + (elapsedMs * refillPerSec / 1000.0))
end

local allowed = 0
local retryAfterMs = 0

if tokens >= permits then
    tokens = tokens - permits
    allowed = 1
else
    -- What the client actually needs to know. Ceiling, not floor: telling a
    -- client to come back 1 ms early guarantees a second rejection, and two
    -- rejections is how a polite client learns to stop being polite.
    retryAfterMs = math.ceil(((permits - tokens) * 1000.0) / refillPerSec)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', nowMs)
-- Refreshed on every touch, so buckets for merchants who have gone away are
-- reclaimed without a sweeper. Without this the keyspace grows forever and the
-- limiter becomes a memory leak with a feature.
redis.call('EXPIRE', KEYS[1], ttlSeconds)

return { allowed, math.floor(tokens), retryAfterMs }
