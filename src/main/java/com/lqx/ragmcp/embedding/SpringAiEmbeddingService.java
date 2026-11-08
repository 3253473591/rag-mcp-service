package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Production embedding implementation backed by a Spring AI {@link EmbeddingModel}
 * (Ollama bge-m3).
 *
 * <p>Contract (docs/11-retrieval-and-eval-spec.md, docs/04-boundaries-and-errors.md section 4):</p>
 * <ul>
 *   <li>batches of 16~32 texts (configured 32);</li>
 *   <li>up to 2 retries with 500ms / 1000ms backoff;</li>
 *   <li>timeout 120s per batch -&gt; ERR-EMBEDDING_TIMEOUT (504);</li>
 *   <li>unavailable / wrong dimension / zero vector -&gt; ERR-EMBEDDING_UNAVAILABLE (503).</li>
 * </ul>
 */
public class SpringAiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);
    private static final int MAX_BATCH_SIZE = 32;

    private final Supplier<EmbeddingModel> modelSupplier;
    private final EmbeddingProperties properties;
    private final ExecutorService executor;

    public SpringAiEmbeddingService(Supplier<EmbeddingModel> modelSupplier, EmbeddingProperties properties) {
        this.modelSupplier = modelSupplier;
        this.properties = properties;
        int poolSize = Math.max(1, properties.getMaxConcurrentCalls());
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "embedding-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel model = modelSupplier.get();
        if (model == null) {
            throw ApiException.embeddingUnavailable("embedding 模型未配置，请检查 spring.ai.model.embedding 与 Ollama 配置");
        }
        int batchSize = normalizeBatchSize(properties.getBatchSize());
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            result.addAll(embedBatch(model, texts.subList(start, end)));
        }
        log.debug("embedded texts={} batches={} model={}", texts.size(),
                (texts.size() + batchSize - 1) / batchSize, properties.getModel());
        return result;
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }

    @Override
    public int dimension() {
        return properties.getDimension();
    }

    static int normalizeBatchSize(int configured) {
        if (configured < 1) {
            return MAX_BATCH_SIZE;
        }
        return Math.min(configured, MAX_BATCH_SIZE);
    }

    private List<float[]> embedBatch(EmbeddingModel model, List<String> batch) {
        int attempts = Math.max(0, properties.getRetries()) + 1;
        ApiException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                List<float[]> vectors = callWithTimeout(model, batch);
                validate(vectors, batch.size());
                return vectors;
            } catch (ApiException ex) {
                last = ex;
                if (attempt < attempts - 1) {
                    long backoff = properties.getRetryBackoffMs() * (1L << attempt);
                    log.warn("embedding attempt {} failed ({}), retrying in {}ms", attempt + 1,
                            ex.errorCode().code(), backoff);
                    sleep(backoff);
                }
            }
        }
        // last is non-null: attempts >= 1 and the loop only exits after a failure
        throw last;
    }

    private List<float[]> callWithTimeout(EmbeddingModel model, List<String> batch) {
        Future<List<float[]>> future = executor.submit(() -> model.embed(batch));
        try {
            return future.get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("embedding timed out after {}s for batch size {}", properties.getTimeoutSeconds(), batch.size());
            throw ApiException.embeddingTimeout("embedding 超时（" + properties.getTimeoutSeconds() + "s）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ApiException.embeddingUnavailable("embedding 调用被中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (isTimeout(cause)) {
                log.warn("embedding timeout: {}", cause.toString());
                throw ApiException.embeddingTimeout("embedding 超时（" + properties.getTimeoutSeconds() + "s）");
            }
            log.warn("embedding call failed: {}", cause.toString());
            throw ApiException.embeddingUnavailable("embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪");
        }
    }

    private void validate(List<float[]> vectors, int expectedCount) {
        int dimension = properties.getDimension();
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
                throw ApiException.embeddingUnavailable("embedding 返回零向量，拒绝入库");
            }
        }
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current.getClass().getName().toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
