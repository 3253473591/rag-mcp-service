package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.EmbeddingProperties;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEmbeddingBatcherTest {

    private final List<QueryEmbeddingBatcher> batchers = new ArrayList<>();

    @AfterEach
    void closeBatchers() {
        for (QueryEmbeddingBatcher batcher : batchers) {
            batcher.close();
        }
        batchers.clear();
    }

    @Test
    void concurrentQueriesAreCoalescedIntoFewerEmbedCallsAndKeepOrder() throws Exception {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(8, 500, 0, 60, 100, 2, 10_000));

        int concurrency = 10;
        ExecutorService callers = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Future<float[]>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                futures.add(callers.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return batcher.embedQuery("query-" + index);
                }));
            }
            for (int i = 0; i < concurrency; i++) {
                assertThat(futures.get(i).get(10, TimeUnit.SECONDS))
                        .containsExactly(FakeEmbeddingService.vectorFor("query-" + i));
            }
        } finally {
            callers.shutdownNow();
        }

        assertThat(delegate.calls.get()).isLessThan(concurrency);
        assertThat(delegate.batches).hasSize(2);
        assertThat(delegate.batches).extracting(List::size).containsExactlyInAnyOrder(8, 2);
        assertThat(delegate.totalTexts()).isEqualTo(concurrency);
    }

    @Test
    void cacheHitDoesNotCallEmbeddingAgainAndReturnsCopy() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(1, 0, 10, 60, 20, 1, 10_000));

        float[] first = batcher.embedQuery("same query");
        float[] second = batcher.embedQuery("same query");

        assertThat(delegate.calls.get()).isEqualTo(1);
        assertThat(second).containsExactly(first);
        assertThat(second).isNotSameAs(first);
        second[0] = 42.0f;
        assertThat(batcher.embedQuery("same query")[0]).isNotEqualTo(42.0f);

        batcher.embedQuery("different query");
        assertThat(delegate.calls.get()).isEqualTo(2);
    }

    @Test
    void failureCodePropagatesToEveryCallerInTheBatch() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        delegate.failure = ApiException.embeddingTimeout("upstream timeout");
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(8, 100, 0, 60, 20, 1, 10_000));

        assertThatThrownBy(() -> batcher.embedQuery("one"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));
        assertThat(delegate.calls.get()).isEqualTo(1);
    }

    @Test
    void unavailableFailurePropagatesAs503() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        delegate.failure = ApiException.embeddingUnavailable("ollama down");
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(8, 100, 0, 60, 20, 1, 10_000));

        assertThatThrownBy(() -> batcher.embedQuery("one"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void wrongDimensionOrZeroVectorFailsAs503() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        delegate.vectorOverride = List.of(new float[16]);
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(1, 0, 0, 60, 20, 1, 10_000));
        assertThatThrownBy(() -> batcher.embedQuery("one"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));

        CountingEmbeddingService zero = new CountingEmbeddingService();
        zero.vectorOverride = List.of(new float[1024]);
        QueryEmbeddingBatcher zeroBatcher = batcher(zero,
                properties(1, 0, 0, 60, 20, 1, 10_000));
        assertThatThrownBy(() -> zeroBatcher.embedQuery("one"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void queueOverflowFailsFastWith503InsteadOfBlocking() throws Exception {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        delegate.release = new CountDownLatch(1);
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(1, 1, 0, 60, 1, 1, 30_000));

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<float[]> first = callers.submit(() -> batcher.embedQuery("first"));
            assertThat(delegate.started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<float[]> second = callers.submit(() -> batcher.embedQuery("second"));
            Thread.sleep(200);
            assertThatThrownBy(() -> batcher.embedQuery("third"))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));

            delegate.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(1024);
            assertThat(second.get(10, TimeUnit.SECONDS)).hasSize(1024);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void callerWaitTimeoutReturns504AndCloseDoesNotHang() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        delegate.delayMs = 20_000;
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(1, 0, 0, 60, 20, 1, 200));

        assertThatThrownBy(() -> batcher.embedQuery("slow"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(6), batcher::close);
    }

    @Test
    void closeFailsQueuedAndWaitingCallersInsteadOfHanging() throws Exception {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        QueryEmbeddingBatcher batcher = batcher(delegate,
                properties(8, 5_000, 0, 60, 20, 1, 60_000));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<float[]> future = caller.submit(() -> batcher.embedQuery("pending"));
            Thread.sleep(200);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), batcher::close);
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex.getCause()).errorCode())
                            .isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void metricsRecordRequestsBatchAndCacheStats() {
        CountingEmbeddingService delegate = new CountingEmbeddingService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryEmbeddingBatcher batcher = new QueryEmbeddingBatcher(delegate,
                properties(4, 0, 10, 60, 20, 1, 10_000), registry);
        batchers.add(batcher);

        batcher.embedQuery("cached query");
        batcher.embedQuery("cached query");

        assertThat(registry.counter("rag.query.embedding.requests").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.query.embedding.cache.hits").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.query.embedding.cache.misses").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.query.embedding.batches").count()).isEqualTo(1.0);
        assertThat(registry.summary("rag.query.embedding.batch.size").count()).isEqualTo(1);
    }

    @Test
    void queryNormalizationCollapsesWhitespaceAndUnicodeWidth() {
        assertThat(QueryEmbeddingBatcher.normalizeQuery("  高血压  诊断  "))
                .isEqualTo("高血压 诊断");
        assertThat(QueryEmbeddingBatcher.normalizeQuery("ＡＢＣ　１２３"))
                .isEqualTo("ABC 123");
        assertThat(QueryEmbeddingBatcher.normalizeQuery(null)).isEmpty();
    }

    private QueryEmbeddingBatcher batcher(CountingEmbeddingService delegate,
                                          EmbeddingProperties properties) {
        QueryEmbeddingBatcher batcher = new QueryEmbeddingBatcher(delegate, properties,
                new SimpleMeterRegistry());
        batchers.add(batcher);
        return batcher;
    }

    private static EmbeddingProperties properties(int batchSize, long windowMs, int cacheSize,
                                                   long cacheTtlSeconds, int queueCapacity,
                                                   int maxConcurrentBatches, long waitTimeoutMs) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setQueryBatchSize(batchSize);
        properties.setQueryBatchWindowMs(windowMs);
        properties.setQueryCacheSize(cacheSize);
        properties.setQueryCacheTtlSeconds(cacheTtlSeconds);
        properties.setQueryQueueCapacity(queueCapacity);
        properties.setQueryMaxConcurrentBatches(maxConcurrentBatches);
        properties.setQueryWaitTimeoutMs(waitTimeoutMs);
        return properties;
    }

    private static final class CountingEmbeddingService implements EmbeddingService {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<String>> batches = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile ApiException failure;
        private volatile CountDownLatch release;
        private volatile long delayMs;
        private volatile List<float[]> vectorOverride;

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.incrementAndGet();
            batches.add(List.copyOf(texts));
            started.countDown();
            CountDownLatch latch = release;
            if (latch != null) {
                try {
                    if (!latch.await(15, TimeUnit.SECONDS)) {
                        throw ApiException.embeddingUnavailable("test release latch timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ApiException.embeddingUnavailable("interrupted");
                }
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ApiException.embeddingUnavailable("interrupted");
                }
            }
            if (failure != null) {
                throw failure;
            }
            List<float[]> overridden = vectorOverride;
            if (overridden != null) {
                return overridden;
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(FakeEmbeddingService.vectorFor(text));
            }
            return vectors;
        }

        int totalTexts() {
            int total = 0;
            synchronized (batches) {
                for (List<String> batch : batches) {
                    total += batch.size();
                }
            }
            return total;
        }

        @Override
        public String modelName() {
            return "bge-m3";
        }

        @Override
        public int dimension() {
            return FakeEmbeddingService.DIMENSION;
        }
    }
}
