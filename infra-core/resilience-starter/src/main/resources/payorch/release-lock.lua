-- Releases a distributed lock, but only if we still hold it.
--
-- WHY THIS IS A SCRIPT AND NOT A DEL
--
-- The obvious release is `DEL lock:key`, and it is wrong in a way that only
-- shows up under exactly the conditions the lock exists for.
--
-- Holder A acquires with a 60-second TTL. A stalls - a long GC pause, a
-- descheduled container, a slow disk - for 70 seconds. The TTL expires, Redis
-- drops the key, and holder B acquires it legitimately. A then wakes up,
-- finishes its work, and calls DEL. It has just deleted B's lock, and now C can
-- acquire it while B is still running. One stall has produced two concurrent
-- holders, and the second one was created by the FIRST one trying to be tidy.
--
-- Comparing the token before deleting means A's release is a no-op: the value
-- under the key is B's token, not A's, so nothing is deleted and B keeps its
-- lock until its own TTL or its own release.
--
-- The compare and the delete have to be one operation, which is what makes this
-- a script. GET followed by DEL from the application has the same window one
-- statement narrower.
--
-- WHAT THIS STILL DOES NOT FIX
--
-- A is running concurrently with B for those ten seconds regardless. Nothing
-- Redis can do prevents that, because Redis cannot reach into A and stop it.
-- See RedisLock for why that makes this an optimisation rather than a
-- correctness guarantee.

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
