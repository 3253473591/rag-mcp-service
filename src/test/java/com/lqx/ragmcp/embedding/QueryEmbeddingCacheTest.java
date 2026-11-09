package com.lqx.ragmcp.embedding;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEmbeddingCacheTest {

    @Test
    void returnsDefensiveCopiesAndCountsHits() {
        QueryEmbeddingCache cache = new QueryEmbeddingCache(10, TimeUnit.SECONDS.toNanos(60));
        float[] original = {1.0f, 2.0f};
        cache.put("k", original);
        original[0] = 99.0f;

        float[] first = cache.get("k");
        assertThat(first).containsExactly(1.0f, 2.0f);
        first[1] = 88.0f;

        assertThat(cache.get("k")).containsExactly(1.0f, 2.0f);
        assertThat(cache.hits()).isEqualTo(2);
        assertThat(cache.misses()).isZero();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void differentKeysMiss() {
        QueryEmbeddingCache cache = new QueryEmbeddingCache(10, TimeUnit.SECONDS.toNanos(60));
        cache.put("a", new float[]{1.0f});
        assertThat(cache.get("b")).isNull();
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void entryExpiresAfterTtl() throws Exception {
        QueryEmbeddingCache cache = new QueryEmbeddingCache(10, TimeUnit.MILLISECONDS.toNanos(50));
        cache.put("k", new float[]{1.0f});
        assertThat(cache.get("k")).isNotNull();
        Thread.sleep(100);
        assertThat(cache.get("k")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void lruEvictsOldestBeyondCapacity() {
        QueryEmbeddingCache cache = new QueryEmbeddingCache(2, TimeUnit.SECONDS.toNanos(60));
        cache.put("a", new float[]{1.0f});
        cache.put("b", new float[]{2.0f});
        cache.get("a");
        cache.put("c", new float[]{3.0f});

        assertThat(cache.get("a")).isNotNull();
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isNotNull();
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void cacheCanBeDisabled() {
        QueryEmbeddingCache cache = new QueryEmbeddingCache(0, TimeUnit.SECONDS.toNanos(60));
        cache.put("k", new float[]{1.0f});
        assertThat(cache.get("k")).isNull();
        assertThat(cache.enabled()).isFalse();
        assertThat(cache.size()).isZero();

        QueryEmbeddingCache zeroTtl = new QueryEmbeddingCache(10, 0);
        zeroTtl.put("k", new float[]{1.0f});
        assertThat(zeroTtl.get("k")).isNull();
        assertThat(zeroTtl.enabled()).isFalse();
    }
}
