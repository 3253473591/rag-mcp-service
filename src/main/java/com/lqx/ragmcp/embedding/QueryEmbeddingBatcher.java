package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.EmbeddingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Query-only embedding front end: coalesces concurrent retrieval queries into a small number of
 * {@link EmbeddingService#embed(List)} calls and serves repeated queries from a bounded cache.
 *
 * <p>Design boundaries:</p>
 * <ul>
 *   <li>only {@code /api/v1/search} and MCP {@code search_knowledge} use this path; document
 *       ingestion keeps calling {@link EmbeddingService} directly with the original batch
 *       semantics;</li>
 *   <li>a short window (default 5ms) collects concurrent queries, up to
 *       {@code app.embedding.query-batch-size} (default 8) per call;</li>
 *   <li>bounded queue: when full, the request fails with {@code ERR-EMBEDDING_UNAVAILABLE}
 *       instead of waiting forever or silently returning an empty result;</li>
 *   <li>failures/timeouts from the delegate propagate to every caller in the batch with their
 *       original error code;</li>
 *   <li>the dispatcher and batch workers are daemon threads, stopped on context close; pending
 *       callers are completed exceptionally so shutdown cannot leak waiters.</li>
 * </ul>
 *
 * <p>The cache cannot make the unique-query concurrency benchmark look better: every benchmark
 * query differs, so hits are expected to be zero. A separate repeated-query experiment
 * demonstrates the cache (see docs/acceptance/phase6b-report.md).</p>
 */
public class QueryEmbeddingBatcher implements QueryEmbeddingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbeddingBatcher.class);

    private static final int MAX_BATCH_SIZE = 32;
    private static final long CLOSE_TIMEOUT_SECONDS = 3;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final EmbeddingService delegate;
    private final String modelName;
    private final int dimension;
    private final int batchSize;
    private final long batchWindowNanos;
    private final long waitTimeoutMs;

    private final QueryEmbeddingCache cache;
    private final ArrayBlockingQueue<Pending> queue;
    private final Set<Pending> pendingSet = ConcurrentHashMap.newKeySet();
    private final ExecutorService dispatcher;
    private final ThreadPoolExecutor batchExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private final Counter requests;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter batches;
    private final Counter failures;
    private final Counter rejected;
    private final Counter waitTimeouts;
    private final DistributionSummary batchSizes;
    private final Timer batchLatency;

    public QueryEmbeddingBatcher(EmbeddingService delegate, EmbeddingProperties properties,
                                 MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.modelName = delegate.modelName();
        this.dimension = delegate.dimension();

        int configuredBatchSize = properties.getQueryBatchSize();
        this.batchSize = Math.max(1, Math.min(configuredBatchSize, MAX_BATCH_SIZE));
        long windowMs = Math.max(0L, Math.min(properties.getQueryBatchWindowMs(), 1000L));
        this.batchWindowNanos = TimeUnit.MILLISECONDS.toNanos(windowMs);
        this.waitTimeoutMs = Math.max(100L, properties.getQueryWaitTimeoutMs());

        long ttlNanos = TimeUnit.SECONDS.toNanos(Math.max(0L, properties.getQueryCacheTtlSeconds()));
        this.cache = new QueryEmbeddingCache(properties.getQueryCacheSize(), ttlNanos);

        int queueCapacity = Math.max(1, properties.getQueryQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(queueCapacity);

        this.dispatcher = Executors.newSingleThreadExecutor(
                runnable -> daemonThread(runnable, "query-embedding-dispatcher"));
        int concurrentBatches = Math.max(1, Math.min(properties.getQueryMaxConcurrentBatches(), 32));
        this.batchExecutor = new ThreadPoolExecutor(
                concurrentBatches, concurrentBatches, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> daemonThread(runnable, "query-embedding-batch"),
                new ThreadPoolExecutor.AbortPolicy());

        this.requests = Counter.builder("rag.query.embedding.requests")
                .description("Query embedding calls received (cache hits included)")
                .register(meterRegistry);
        this.cacheHits = Counter.builder("rag.query.embedding.cache.hits")
                .description("Query embedding cache hits")
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("rag.query.embedding.cache.misses")
                .description("Query embedding cache misses")
                .register(meterRegistry);
        this.batches = Counter.builder("rag.query.embedding.batches")
                .description("Batched delegate embed(List) calls")
                .register(meterRegistry);
        this.failures = Counter.builder("rag.query.embedding.failures")
                .description("Queries failed by a failed delegate batch or shutdown")
                .register(meterRegistry);
        this.rejected = Counter.builder("rag.query.embedding.rejected")
                .description("Queries rejected because the queue/executor was full or closed")
                .register(meterRegistry);
        this.waitTimeouts = Counter.builder("rag.query.embedding.wait.timeouts")
                .description("Queries whose caller wait timed out")
                .register(meterRegistry);
        this.batchSizes = DistributionSummary.builder("rag.query.embedding.batch.size")
                .description("Queries per delegate embed(List) call")
                .register(meterRegistry);
        this.batchLatency = Timer.builder("rag.query.embedding.batch.latency")
                .description("Delegate embed(List) batch latency")
                .register(meterRegistry);
        Gauge.builder("rag.query.embedding.queue.depth", queue, ArrayBlockingQueue::size)
                .description("Queries waiting to be batched")
                .register(meterRegistry);

        this.dispatcher.execute(this::dispatchLoop);
    }

    @Override
    public float[] embedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw ApiException.validationField("query", "query 不能为空");
        }
        if (closed.get()) {
            rejected.increment();
            throw ApiException.embeddingUnavailable("query embedding 已关闭");
        }
        requests.increment();

        String normalized = normalizeQuery(query);
        String cacheKey = modelName + '\u0000' + normalized;
        float[] cached = cache.get(cacheKey);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        cacheMisses.increment();

        Pending pending = new Pending(normalized, cacheKey);
        synchronized (lifecycleLock) {
            // Re-check under the lock so close() cannot miss a query that is being admitted.
            if (closed.get()) {
                rejected.increment();
                throw ApiException.embeddingUnavailable("query embedding 已关闭");
            }
            pendingSet.add(pending);
            if (!queue.offer(pending)) {
                pendingSet.remove(pending);
                rejected.increment();
                throw ApiException.embeddingUnavailable("query embedding 队列已满，请稍后重试");
            }
        }

        try {
            return pending.future.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.future.cancel(false);
            waitTimeouts.increment();
            log.warn("query embedding wait timed out after {}ms (batchSize={} pending={})",
                    waitTimeoutMs, batchSize, pendingSet.size());
            throw ApiException.embeddingTimeout("query embedding 等待超时（" + waitTimeoutMs + "ms）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ApiException.embeddingUnavailable("query embedding 等待被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            log.warn("query embedding failed type={}", cause.getClass().getSimpleName());
            throw ApiException.embeddingUnavailable("embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪");
        }
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        dispatcher.shutdownNow();
        List<Runnable> neverStarted = batchExecutor.shutdownNow();
        for (Runnable runnable : neverStarted) {
            if (runnable instanceof BatchTask task) {
                task.failClosed();
            }
        }
        failQueued(ApiException.embeddingUnavailable("query embedding 已关闭"));
        try {
            dispatcher.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            batchExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // Anything still registered must not leave a caller blocked until waitTimeout.
        for (Pending pending : new ArrayList<>(pendingSet)) {
            finish(pending, null, ApiException.embeddingUnavailable("query embedding 已关闭"));
        }
        pendingSet.clear();
        log.info("query embedding batcher closed (batches={}, cacheHits={}, cacheMisses={})",
                batches.count(), cacheHits.count(), cacheMisses.count());
    }

    static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).strip();
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    private void dispatchLoop() {
        try {
            while (!closed.get()) {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Pending> batch = new ArrayList<>(batchSize);
                batch.add(first);
                long deadline = System.nanoTime() + batchWindowNanos;
                while (batch.size() < batchSize) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    Pending next;
                    try {
                        next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException ex) {
                        if (closed.get()) {
                            break;
                        }
                        continue;
                    }
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                submit(batch);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.error("query embedding dispatcher failed; failing fast", ex);
            closed.set(true);
        } finally {
            failQueued(ApiException.embeddingUnavailable("query embedding 已关闭"));
        }
    }

    private void submit(List<Pending> batch) {
        if (closed.get()) {
            failBatch(batch, ApiException.embeddingUnavailable("query embedding 已关闭"));
            return;
        }
        try {
            batchExecutor.execute(new BatchTask(batch));
        } catch (RejectedExecutionException ex) {
            rejected.increment();
            failBatch(batch, ApiException.embeddingUnavailable("query embedding 队列已满，请稍后重试"));
        }
    }

    private void runBatch(List<Pending> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (closed.get()) {
            failBatch(batch, ApiException.embeddingUnavailable("query embedding 已关闭"));
            return;
        }
        batches.increment();
        batchSizes.record(batch.size());
        List<String> texts = new ArrayList<>(batch.size());
        for (Pending pending : batch) {
            texts.add(pending.query);
        }
        long startedAt = System.nanoTime();
        try {
            List<float[]> vectors = delegate.embed(texts);
            validate(vectors, batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Pending pending = batch.get(i);
                float[] vector = vectors.get(i);
                cache.put(pending.cacheKey, vector);
                finish(pending, vector.clone(), null);
            }
            log.debug("query embedding batch completed size={}", batch.size());
        } catch (ApiException ex) {
            failures.increment(batch.size());
            log.warn("query embedding batch failed size={} code={}", batch.size(), ex.errorCode().code());
            failBatch(batch, ex);
        } catch (RuntimeException ex) {
            failures.increment(batch.size());
            log.warn("query embedding batch failed size={} type={}", batch.size(), ex.getClass().getSimpleName());
            failBatch(batch, ApiException.embeddingUnavailable(
                    "embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪"));
        } finally {
            batchLatency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void validate(List<float[]> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw ApiException.embeddingUnavailable("embedding 返回数量与输入不一致");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dimension) {
                throw ApiException.embeddingUnavailable(
                        "embedding 维度不是 " + dimension + "，请检查模型配置（bge-m3 应为 1024）");
            }
            boolean nonZero = false;
            for (float value : vector) {
                if (value != 0.0f) {
                    nonZero = true;
                    break;
                }
            }
            if (!nonZero) {
                throw ApiException.embeddingUnavailable("embedding 返回零向量，拒绝检索");
            }
        }
    }

    private void failBatch(List<Pending> batch, ApiException failure) {
        for (Pending pending : batch) {
            finish(pending, null, failure);
        }
    }

    private void failQueued(ApiException failure) {
        Pending pending = queue.poll();
        while (pending != null) {
            finish(pending, null, failure);
            pending = queue.poll();
        }
    }

    private boolean finish(Pending pending, float[] vector, Throwable failure) {
        if (!pending.done.compareAndSet(false, true)) {
            return false;
        }
        pendingSet.remove(pending);
        if (failure != null) {
            pending.future.completeExceptionally(failure);
        } else {
            pending.future.complete(vector);
        }
        return true;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private final class Pending {
        private final String query;
        private final String cacheKey;
        private final CompletableFuture<float[]> future = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean(false);

        private Pending(String query, String cacheKey) {
            this.query = query;
            this.cacheKey = cacheKey;
        }
    }

    private final class BatchTask implements Runnable {
        private final List<Pending> batch;

        private BatchTask(List<Pending> batch) {
            this.batch = batch;
        }

        @Override
        public void run() {
            runBatch(batch);
        }

        private void failClosed() {
            failures.increment(batch.size());
            failBatch(batch, ApiException.embeddingUnavailable("query embedding 已关闭"));
        }
    }
}
