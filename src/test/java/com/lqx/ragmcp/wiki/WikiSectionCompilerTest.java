package com.lqx.ragmcp.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.chunk.TokenCounter;
import com.lqx.ragmcp.config.WikiProperties;
import com.lqx.ragmcp.page.PageSourceChunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki JSON contract: strict parsing, one parse retry (then skip, never an empty page), source
 * provenance constrained to supplied chunks, and transport retry for 429.
 */
class WikiSectionCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<StubResponse> responses = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private WikiSectionCompiler compiler;
    private WikiSection section;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = Math.min(calls.getAndIncrement(), responses.size() - 1);
            StubResponse response = responses.get(index);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        WikiProperties properties = new WikiProperties();
        properties.setApiKey("test-key-not-real");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setMaxAttempts(2);
        properties.setRetryBackoffMs(1L);
        properties.setParseRetries(1);
        properties.setTimeoutSeconds(5);
        compiler = new WikiSectionCompiler(new DeepSeekClient(properties, objectMapper), properties,
                objectMapper, new TokenCounter());

        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        section = new WikiSection(UUID.randomUUID(), "handbook", "2025", "员工手册",
                "handbook@2025#1.1 年假", "1.1 年假", "1.1", List.of("1.1"), 0, 3, 3,
                List.of(
                        new PageSourceChunk(chunkA, 0, "员工每年享有十天年假。", 10, "1.1",
                                "1.1 年假", 3, 3),
                        new PageSourceChunk(chunkB, 1, "年假需提前申请。", 8, "1.1",
                                "1.1 年假", 3, 3)));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validJsonBecomesAWikiPageWithSuppliedSources() {
        responses.add(new StubResponse(200, envelope(wikiJson("年假", "员工每年十天年假。",
                List.of("每年十天年假"), List.of("1.2"), List.of()))));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.SUCCESS);
        assertThat(outcome.page().title()).isEqualTo("年假");
        assertThat(outcome.page().summary()).isEqualTo("员工每年十天年假。");
        assertThat(outcome.page().keyPoints()).containsExactly("每年十天年假");
        assertThat(outcome.page().crossRefs()).containsExactly("1.2");
        // The model returned no sources; the page must record the chunks that were supplied.
        assertThat(outcome.page().sourceChunkIds()).hasSize(2)
                .containsExactlyInAnyOrder(
                        section.chunks().get(0).chunkId(), section.chunks().get(1).chunkId());
        assertThat(outcome.page().embeddingText())
                .isEqualTo("年假\n员工每年十天年假。\n每年十天年假");
        assertThat(outcome.page().body()).contains("\"summary\"").contains("\"key_points\"");
        assertThat(outcome.llmCalls()).isEqualTo(1);
        assertThat(outcome.promptTokens()).isEqualTo(10);
        assertThat(outcome.completionTokens()).isEqualTo(5);
    }

    @Test
    void inventedSourceIdsAreDroppedAndFallBackToSuppliedChunks() {
        responses.add(new StubResponse(200, envelope(wikiJson("年假", "摘要",
                List.of("要点"), List.of(), List.of(UUID.randomUUID().toString())))));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.SUCCESS);
        assertThat(outcome.page().sourceChunkIds()).containsExactly(
                section.chunks().get(0).chunkId(), section.chunks().get(1).chunkId());
    }

    @Test
    void malformedJsonIsRetriedOnceThenSkipped() {
        responses.add(new StubResponse(200, envelope("这不是 JSON")));
        responses.add(new StubResponse(200, envelope("{\"title\":\"缺 summary\"}")));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.SKIPPED);
        assertThat(outcome.page()).isNull();
        assertThat(outcome.reason()).contains("无法解析").contains("summary");
        assertThat(outcome.llmCalls()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
        // Both calls were billed even though no page was written.
        assertThat(outcome.promptTokens()).isEqualTo(20);
    }

    @Test
    void malformedJsonIsRecoveredOnTheParseRetry() {
        responses.add(new StubResponse(200, envelope("not json")));
        responses.add(new StubResponse(200, envelope(wikiJson("年假", "员工每年十天年假。",
                List.of("十天"), List.of(), List.of()))));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.SUCCESS);
        assertThat(outcome.page().title()).isEqualTo("年假");
        assertThat(outcome.llmCalls()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rateLimitedCallIsRetriedWithBackoff() {
        responses.add(new StubResponse(429, "{\"error\":\"rate limited\"}"));
        responses.add(new StubResponse(200, envelope(wikiJson("年假", "摘要",
                List.of("要点"), List.of(), List.of()))));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.SUCCESS);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void transportFailureIsFailedNotSkipped() {
        responses.add(new StubResponse(401, "{\"error\":\"unauthorized\"}"));

        WikiSectionCompiler.Outcome outcome = compiler.compile(section);

        assertThat(outcome.status()).isEqualTo(WikiSectionCompiler.Status.FAILED);
        assertThat(outcome.reason()).contains("HTTP 401");
        assertThat(outcome.page()).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    private String envelope(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5,
                            "total_tokens", 15)));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String wikiJson(String title, String summary, List<String> keyPoints,
                            List<String> crossRefs, List<String> sources) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", title,
                    "summary", summary,
                    "key_points", keyPoints,
                    "cross_refs", crossRefs,
                    "sources", sources));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record StubResponse(int status, String body) {
    }
}
