package com.lqx.ragmcp.embedding;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, TTL-based LRU cache for successful, non-zero query vectors.
 *
 * <p>Keys must already contain the model name and a normalized query (see
 * {@link QueryEmbeddingBatcher#normalizeQuery(String)}). Vectors are copied on both write and
 * read so callers cannot mutate cached state. Disabled when {@code maxSize <= 0} or
 * {@code ttlNanos <= 0}.</p>
 *
 * <p>This cache is deliberately not a performance trick for the unique-query concurrency
 * benchmark: {@code perf-concurrency.ps1} changes every query, so the expected hit rate there is
 * zero. It exists for real clients that repeat the same question.</p>
 */
final class QueryEmbeddingCache {

    private final int maxSize;
    private final long ttlNanos;
    private final LinkedHashMap<String, Entry> entries;

    private long hits;
    private long misses;

    QueryEmbeddingCache(int maxSize, long ttlNanos) {
        this.maxSize = Math.max(0, maxSize);
        this.ttlNanos = Math.max(0L, ttlNanos);
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    boolean enabled() {
        return maxSize > 0 && ttlNanos > 0;
    }

    /** Returns a defensive copy, or {@code null} on miss/expiry/disabled cache. */
    float[] get(String key) {
        if (!enabled() || key == null) {
            misses++;
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            entries.remove(key);
            misses++;
            return null;
        }
        hits++;
        return entry.vector().clone();
    }

    /** Stores a copy of a successful, non-zero vector. */
    void put(String key, float[] vector) {
        if (!enabled() || key == null || vector == null || vector.length == 0) {
            return;
        }
        entries.put(key, new Entry(vector.clone(), System.nanoTime() + ttlNanos));
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxSize && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    long hits() {
        return hits;
    }

    long misses() {
        return misses;
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }

    private record Entry(float[] vector, long expiresAtNanos) {
    }
}
