package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.config.EmbeddingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reports whether the Ollama endpoint is reachable and lists the configured embedding
 * model. The service still starts when this is DOWN; ingestion returns 503/504.
 */
@Component
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final EmbeddingProperties properties;
    private final String baseUrl;

    public EmbeddingHealthIndicator(EmbeddingProperties properties,
                                    @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    @Override
    public Health health() {
        String url = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Health.down()
                        .withDetail("model", properties.getModel())
                        .withDetail("reason", "Ollama HTTP " + response.statusCode())
                        .build();
            }
            boolean modelPresent = hasModel(response.body(), properties.getModel());
            if (!modelPresent) {
                return Health.down()
                        .withDetail("model", properties.getModel())
                        .withDetail("reason", "embedding 模型未拉取")
                        .build();
            }
            return Health.up()
                    .withDetail("model", properties.getModel())
                    .withDetail("dimension", properties.getDimension())
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("model", properties.getModel())
                    .withDetail("reason", "Ollama 不可达: " + ex.getClass().getSimpleName())
                    .build();
        }
    }

    /**
     * Ollama lists tagged models (e.g. {@code bge-m3:latest}) while the configured model may
     * be the untagged name, so match both the exact name and the {@code name:} prefix.
     */
    static boolean hasModel(String body, String model) {
        if (body == null || model == null || model.isBlank()) {
            return false;
        }
        return body.contains("\"name\":\"" + model + "\"")
                || body.contains("\"name\":\"" + model + ":")
                || body.contains("\"model\":\"" + model + "\"")
                || body.contains("\"model\":\"" + model + ":");
    }
}
