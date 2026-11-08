package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.EmbeddingProperties;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SpringAiEmbeddingServiceTest {

    private static EmbeddingProperties properties(int batchSize, int retries, long backoffMs, int timeoutSeconds) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setBatchSize(batchSize);
        properties.setRetries(retries);
        properties.setRetryBackoffMs(backoffMs);
        properties.setTimeoutSeconds(timeoutSeconds);
        return properties;
    }

    @Test
    void tEmbed01_retriesTwiceThenSucceeds() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("ollama down");
            }
            List<String> batch = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (String text : batch) {
                vectors.add(FakeEmbeddingService.vectorFor(text));
            }
            return vectors;
        }).when(model).embed(anyList());

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 2, 1, 5));

        List<float[]> vectors = service.embed(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1024);
        verify(model, times(3)).embed(anyList());
    }

    @Test
    void tEmbed02_failsWith503AfterRetries() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        doThrow(new IllegalStateException("connection refused")).when(model).embed(anyList());
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 2, 1, 5));

        assertThatThrownBy(() -> service.embed(List.of("alpha")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
        verify(model, times(3)).embed(anyList());
    }

    @Test
    void tEmbed03_rejectsWrongDimension() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        doAnswer(invocation -> List.of(new float[512])).when(model).embed(anyList());
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 0, 1, 5));

        assertThatThrownBy(() -> service.embed(List.of("alpha")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void tEmbed04_rejectsZeroVector() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        doAnswer(invocation -> List.of(new float[1024])).when(model).embed(anyList());
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 0, 1, 5));

        assertThatThrownBy(() -> service.embed(List.of("alpha")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void tEmbed05_batchesSixteenToThirtyTwo() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        List<Integer> batchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            List<float[]> vectors = new ArrayList<>();
            for (String text : batch) {
                vectors.add(FakeEmbeddingService.vectorFor(text));
            }
            return vectors;
        }).when(model).embed(anyList());

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(16, 0, 1, 5));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            texts.add("text-" + i);
        }

        List<float[]> vectors = service.embed(texts);

        assertThat(vectors).hasSize(40);
        assertThat(batchSizes).containsExactly(16, 16, 8);
    }

    @Test
    void tEmbed06_timeoutReturns504() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        doAnswer(invocation -> {
            Thread.sleep(2000);
            return List.of(FakeEmbeddingService.vectorFor("slow"));
        }).when(model).embed(anyList());
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 0, 1, 1));

        assertThatThrownBy(() -> service.embed(List.of("alpha")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));
    }

    @Test
    void missingModelReturns503() {
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> null,
                properties(32, 0, 1, 5));

        assertThatThrownBy(() -> service.embed(List.of("alpha")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void tEmbed07_allowsConcurrentEmbeddingCallsForSearch() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, (left, right) -> Math.max(left, right));
            Thread.sleep(150);
            active.decrementAndGet();
            return List.of(FakeEmbeddingService.vectorFor("concurrent"));
        }).when(model).embed(anyList());

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 0, 1, 5));
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<float[]>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(callers.submit(() -> service.embed(List.of("alpha"))));
            }
            for (Future<List<float[]>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).hasSize(1);
            }
        } finally {
            callers.shutdownNow();
        }
        // The old fixed pool of 2 would cap this at 2; search needs 10 concurrent calls.
        assertThat(maxActive.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void emptyInputReturnsEmptyList() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(() -> model,
                properties(32, 2, 1, 5));

        assertThat(service.embed(List.of())).isEmpty();
    }
}
