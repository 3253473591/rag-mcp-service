package com.lqx.ragmcp.support;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.embedding.EmbeddingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic 1024-dim fake embedding for tests. Never calls Ollama.
 */
public class FakeEmbeddingService implements EmbeddingService {

    public static final int DIMENSION = 1024;
    public static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);

    @Override
    public List<float[]> embed(List<String> texts) {
        if (FAIL_NEXT.compareAndSet(true, false)) {
            throw ApiException.embeddingUnavailable("fake embedding unavailable");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorFor(text));
        }
        return vectors;
    }

    @Override
    public String modelName() {
        return "bge-m3";
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    public static float[] vectorFor(String text) {
        String safe = text == null ? "" : text;
        Random random = new Random(31L * safe.hashCode() + safe.length());
        float[] vector = new float[DIMENSION];
        double norm = 0;
        for (int i = 0; i < DIMENSION; i++) {
            float value = (float) random.nextGaussian();
            vector[i] = value;
            norm += value * value;
        }
        double length = Math.sqrt(norm);
        if (length == 0) {
            vector[0] = 1f;
            return vector;
        }
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }
}
