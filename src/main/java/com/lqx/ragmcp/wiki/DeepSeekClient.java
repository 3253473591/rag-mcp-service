package com.lqx.ragmcp.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.config.WikiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible chat client for DeepSeek ({@code POST /chat/completions}).
 *
 * <p><b>Secret handling.</b> The API key is only placed in the {@code Authorization} header. It is
 * never logged, never included in an exception message and never stored anywhere else. Failures
 * are reported by status code and exception class only.</p>
 *
 * <p><b>Retry.</b> 429 and 5xx responses, plus I/O failures, are retried with exponential
 * backoff. {@code Retry-After} is honoured when present (capped at 30s). 4xx responses other than
 * 429 fail immediately because retrying a bad key or a bad request only burns quota.</p>
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final int MAX_RETRY_AFTER_SECONDS = 30;

    private final WikiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;

    public DeepSeekClient(WikiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30, properties.getTimeoutSeconds())))
                .build();
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/chat/completions");
    }

    /**
     * Calls the chat completion endpoint and returns the assistant message content plus usage.
     *
     * @throws LlmCallException  transport/HTTP failure; the caller reports it as a failed section
     * @throws LlmOutputException 200 response without a usable {@code choices[0].message.content}
     */
    public ChatResult chat(String systemPrompt, String userPrompt) {
        String requestBody = buildRequestBody(systemPrompt, userPrompt);
        int attempts = properties.getMaxAttempts();
        LlmCallException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    return parseResponse(response.body());
                }
                boolean retryable = status == 429 || status >= 500;
                lastFailure = new LlmCallException("DeepSeek 返回 HTTP " + status);
                if (!retryable) {
                    throw lastFailure;
                }
                if (attempt < attempts) {
                    long backoff = backoffMillis(attempt, response.headers().firstValue("Retry-After")
                            .orElse(null));
                    log.warn("deepseek call failed status={} attempt={}/{} retryingInMs={}",
                            status, attempt, attempts, backoff);
                    sleep(backoff);
                }
            } catch (IOException ex) {
                lastFailure = new LlmCallException(
                        "DeepSeek 网络错误（" + ex.getClass().getSimpleName() + "）");
                if (attempt < attempts) {
                    long backoff = backoffMillis(attempt, null);
                    log.warn("deepseek call io failure attempt={}/{} type={} retryingInMs={}",
                            attempt, attempts, ex.getClass().getSimpleName(), backoff);
                    sleep(backoff);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("DeepSeek 调用被中断");
            }
        }
        throw lastFailure == null ? new LlmCallException("DeepSeek 调用失败") : lastFailure;
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("max_tokens", properties.getMaxTokens());
        body.put("temperature", properties.getTemperature());
        body.put("stream", false);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException ex) {
            // Cannot happen for a Map of primitives; still fail closed without leaking the key.
            throw new LlmCallException("构造 DeepSeek 请求失败");
        }
    }

    private ChatResult parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmOutputException("DeepSeek 响应缺少 choices");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new LlmOutputException("DeepSeek 响应内容为空");
            }
            JsonNode usage = root.path("usage");
            long promptTokens = usage.path("prompt_tokens").asLong(0L);
            long completionTokens = usage.path("completion_tokens").asLong(0L);
            long totalTokens = usage.path("total_tokens").asLong(promptTokens + completionTokens);
            log.debug("deepseek call ok model={} promptTokens={} completionTokens={}",
                    properties.getModel(), promptTokens, completionTokens);
            return new ChatResult(content, promptTokens, completionTokens, totalTokens);
        } catch (LlmOutputException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new LlmOutputException("DeepSeek 响应不是合法 JSON");
        }
    }

    private long backoffMillis(int attempt, String retryAfterHeader) {
        if (retryAfterHeader != null) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                if (seconds > 0) {
                    return Math.min(seconds, MAX_RETRY_AFTER_SECONDS) * 1000L;
                }
            } catch (NumberFormatException ignored) {
                // Retry-After may be an HTTP date; fall through to exponential backoff.
            }
        }
        long base = properties.getRetryBackoffMs();
        return base <= 0 ? 0L : base * (1L << (attempt - 1));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("DeepSeek 重试等待被中断");
        }
    }

    /** 200 response or usage envelope. */
    public record ChatResult(String content, long promptTokens, long completionTokens,
                             long totalTokens) {
    }

    /** Transport/HTTP failure. */
    public static class LlmCallException extends RuntimeException {
        public LlmCallException(String message) {
            super(message);
        }
    }

    /** 200 response whose envelope/content cannot be used. */
    public static class LlmOutputException extends LlmCallException {
        public LlmOutputException(String message) {
            super(message);
        }
    }
}
