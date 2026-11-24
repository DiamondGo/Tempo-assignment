## Code Review

You are reviewing the following code submitted as part of a task to implement an item cache in a highly concurrent application. The anticipated load includes: thousands of reads per second, hundreds of writes per second, tens of concurrent threads.
Your objective is to identify and explain the issues in the implementation that must be addressed before deploying the code to production. Please provide a clear explanation of each issue and its potential impact on production behaviour.

```java
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMs = 60000; // 1 minute

    public static class CacheEntry<V> {
        private final V value;
        private final long timestamp;

        public CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public V getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() - entry.getTimestamp() < ttlMs) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int size() {
        return cache.size();
    }
}
```

## Review

Using `ConcurrentHashMap` is a good base (lock-free reads), and since the `CacheEntry` fields are final, readers always
see a complete entry. That doesn't make the value itself thread-safe, see 10.

Group A are problems in the code itself. Group B depend on how the cache is used and on requirements.

### A. Defects in the code as written

1. **[Critical] Expired entries are never removed (memory leak).**
   `get()` only hides an expired entry; it stays in the map forever unless the same key is written again.
   With hundreds of writes per second over a large or growing key space, the heap grows without bound,
   GC pauses get longer, and the service eventually fails with `OutOfMemoryError`. Even once expiry works, a 60s
   lifetime usually outlives several young GCs, so entries tend to be promoted to the old generation and become
   garbage there, which adds old-gen GC work. Keeping expired entries forever makes this much worse.

2. **[Critical] No size bound and no eviction policy.**
   Even with expiry fixed, nothing limits how many live entries exist. A burst of distinct keys (a traffic spike,
   a bug, or keys derived from user input) can fill the heap within one TTL window. A production cache needs a maximum size
   (or weight) with an eviction policy such as LRU/LFU.

3. **[High] Wall-clock time is used to measure elapsed time.**
   `System.currentTimeMillis()` can jump forward or backward (NTP corrections, VM migration, manual changes).
   A backward jump makes `now - timestamp` small or negative, so entries live much longer than 60s; a forward jump
   expires all entries at once, which causes a reload spike on the backing store. Use a monotonic source such as
   `System.nanoTime()`, ideally behind an injectable clock/ticker so expiry is also testable.

4. **[Medium] `null` handling is inconsistent and misses are ambiguous.**
   A `null` key makes both `put(null, v)` and `get(null)` throw `NullPointerException` (`ConcurrentHashMap` does not
   allow null keys). A `null` value is silently accepted, because the map stores the non-null `CacheEntry` wrapper,
   and then `get()` returns `null` for "cached null", "never cached" and "expired" alike. Callers cannot tell these
   cases apart, so they cannot cache negative results (e.g. "user not found"): a cached null looks like a miss and
   every lookup for a missing item goes to the database again. The contract should either reject null values
   explicitly or return an `Optional`/sentinel that distinguishes "absent" from "cached empty result".

5. **[Medium] `size()` is misleading.**
   It counts expired entries, so it does not reflect the number of usable entries. Under concurrent updates
   `ConcurrentHashMap.size()` is also only an estimate. Using it for monitoring or capacity decisions gives wrong numbers.

### B. Risks that depend on usage and requirements

The class doesn't load data itself, so these depend on how callers use it. With only `get`/`put`, the usual pattern
is cache-aside in the caller.

6. **[High] Cache stampede, if callers use uncoordinated cache-aside loading.**
   Without a loading API, callers typically do "get, on miss load from the database, then put". When a hot key expires,
   all concurrent threads (tens of them) miss at the same moment and all load the same value. This multiplies load on
   the database exactly when it is busiest and can cascade into timeouts. An atomic per-key `get(key, loader)` would let
   one thread load while the others wait for its result.

7. **[High] A stale value can overwrite a fresh one, under the same cache-aside pattern.**
   A slow thread that read an old value from the database can `put` it after a faster thread (or a writer that just
   updated the database) stored the newer value. The old value wins, gets a fresh timestamp, and is served for a full
   minute. `put` is unconditional, so the cache cannot detect this. Coalescing loads per key reduces the window but does
   not close it; preventing it needs coordination with the write path, e.g. invalidating after the source is updated or
   storing a version and refusing older ones.

8. **[Medium] No invalidation API, if changes must become visible quickly.**
   There is no `remove`, `invalidate` or `clear`, so after the underlying data changes, readers can see the old value
   for up to 60s. Whether this blocks production depends on whether the business accepts one minute of staleness.
   In a multi-instance deployment each instance has its own copy, so instances can disagree with each other unless
   invalidation is propagated (e.g. via a message bus).

9. **[Medium] TTL is hard-coded and identical for all entries.**
   60s cannot be tuned per use case or environment without a code change. Entries written together (e.g. after a
   deployment or cache warm-up) also expire together, causing synchronized reload spikes; jitter avoids that.
   This class only does expiry. Refreshing from the source or invalidating on source changes would need a loader or
   change notifications, and should be an explicit requirement if needed.

### C. Low

10. **Mutable values and keys are shared.**
    `get()` returns the cached object itself. If `V` is mutable, a caller modifying it changes what every other
    thread sees, which is a data race. Keys whose `hashCode` changes after insertion become unreachable entries.
    The contract should require immutable keys/values or the cache should copy them.

11. **No observability.**
    There are no hit/miss/eviction/load-time metrics, so it is impossible to tell whether the cache works,
    tune TTL and size, or detect a stampede in production.

12. **API and style.**
    `CacheEntry` is public although it is an implementation detail (a private `record` is enough).
    `ttlMs` is an instance constant that should be a constructor parameter (or `static final`).

## Recommendation

Rather than fixing this hand-rolled cache, use a proven library such as [Caffeine](https://github.com/ben-manes/caffeine).
It solves issues 1, 2, 3 (it measures time with `System.nanoTime()`) and 11 out of the box, and offers local
`invalidate`/`invalidateAll` (issue 8). A `LoadingCache` coalesces concurrent loads of the same key within one instance
(issue 6), and `refreshAfterWrite` or a custom `Expiry` with jitter helps with issue 9. Caffeine does not allow null
values either: a loader returning `null` means "no mapping", so negative results need an explicit wrapper such as
`Optional` (issue 4):

```java
LoadingCache<K, V> cache = Caffeine.newBuilder()
    .maximumSize(100_000)
    .expireAfterWrite(Duration.ofMinutes(1))
    .recordStats()
    .build(key -> loadFromSource(key)); // concurrent loads of the same key are coalesced
```

Caffeine does not keep the cache consistent with the source of truth on its own: stale overwrites from the write path
(issue 7) and invalidation across instances (issue 8) still need an explicit design, such as invalidating after writes
and broadcasting invalidations between instances. If instances need to share cached state (many pods, cold starts after
deployments, repeated loads of the same data), a local cache alone is not enough; a distributed cache such as Redis, or
a local Caffeine L1 in front of a Redis L2, is the usual answer.

If a custom implementation is required, the minimum changes are: a monotonic, injectable clock; removing expired entries
on read with `cache.remove(key, entry)` (conditional, so a fresh entry written concurrently is not deleted) plus periodic
cleanup; a maximum size; and, depending on requirements, `invalidate`/`clear` methods and an atomic `get(key, loader)`.
The loader should not run inside `ConcurrentHashMap.computeIfAbsent`/`compute`: the map locks the key's hash bin while
the function runs, so a slow database call blocks writes to unrelated keys in the same bin, and a loader that touches
the map again can fail with `IllegalStateException` ("Recursive update") or deadlock. Storing a `CompletableFuture<V>`
per key (insert with `putIfAbsent`, complete it outside the map, remove it if the load fails) coalesces loads without
holding the lock.
