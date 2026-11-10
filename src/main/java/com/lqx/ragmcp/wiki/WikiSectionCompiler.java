package com.lqx.ragmcp.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.chunk.TokenCounter;
import com.lqx.ragmcp.config.WikiProperties;
import com.lqx.ragmcp.page.PageSourceChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compiles ONE leaf section into the strict wiki JSON:
 *
 * <pre>{"title":"...","summary":"&lt;=120字","key_points":["..."],
 *       "cross_refs":["4.2.1"],"sources":["&lt;chunkId&gt;"]}</pre>
 *
 * <p>The model is given only this section's chunks. A 200 response whose content is not the
 * required object is retried once ({@code app.wiki.parse-retries=1}); after that the section is
 * reported as <b>skipped</b> and no page is written, so an empty or malformed page can never
 * enter {@code kb_page}. Transport/HTTP failures are reported as <b>failed</b>.</p>
 *
 * <p>{@code sources} is constrained to the chunk ids that were actually supplied. If the model
 * omits them, the page records every supplied chunk instead of inventing provenance.</p>
 */
@Component
public class WikiSectionCompiler {

    private static final Logger log = LoggerFactory.getLogger(WikiSectionCompiler.class);

    static final String SYSTEM_PROMPT = """
            你是企业知识库的 wiki 编译器。你的唯一任务是把"给定材料"压缩成一个结构化 wiki 页面。

            必须遵守：
            1. 只能使用给定材料中的信息，禁止编造、禁止补充材料之外的知识。
            2. 数字、单位、剂量、阈值、日期、条款号必须逐字保留，不得改写、四舍五入或换算。
            3. 不得合并其他小节的内容；材料里没有的内容就不要写。
            4. 只输出一个 JSON 对象，不要输出 Markdown 代码块、解释或任何多余文字。

            输出 JSON 的字段必须且只能是：
            {
              "title": "页面标题，简短描述本节主题",
              "summary": "本节摘要，不超过 120 个汉字",
              "key_points": ["关键点，逐条列出；保留原文数字/单位/剂量/条款号"],
              "cross_refs": ["只列材料中明确出现的其他条款号，如 4.2.1；没有就输出 []"],
              "sources": ["材料中实际支持本页的 chunkId；没有就输出 []"]
            }
            """;

    private final DeepSeekClient client;
    private final WikiProperties properties;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;

    public WikiSectionCompiler(DeepSeekClient client, WikiProperties properties,
                               ObjectMapper objectMapper, TokenCounter tokenCounter) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;
    }

    public Outcome compile(WikiSection section) {
        if (section.chunks() == null || section.chunks().isEmpty()) {
            return Outcome.skipped("小节没有可编译的正文", 0L, 0L, 0);
        }
        String userPrompt = userPrompt(section);
        long promptTokens = 0L;
        long completionTokens = 0L;
        int calls = 0;
        String lastParseFailure = "";
        int attempts = 1 + Math.max(0, properties.getParseRetries());
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                calls++;
                DeepSeekClient.ChatResult result = client.chat(SYSTEM_PROMPT, userPrompt);
                promptTokens += result.promptTokens();
                completionTokens += result.completionTokens();
                WikiPageContent page = parse(section, result.content());
                return Outcome.success(page, promptTokens, completionTokens, calls);
            } catch (DeepSeekClient.LlmOutputException ex) {
                lastParseFailure = ex.getMessage();
                log.warn("wiki compile output rejected section={} attempt={}/{} reason={}",
                        section.sectionLabel(), attempt + 1, attempts, ex.getMessage());
            } catch (DeepSeekClient.LlmCallException ex) {
                log.warn("wiki compile call failed section={} type={} message={}",
                        section.sectionLabel(), ex.getClass().getSimpleName(), ex.getMessage());
                return Outcome.failed("LLM 调用失败: " + ex.getMessage(), promptTokens,
                        completionTokens, calls);
            } catch (RuntimeException ex) {
                log.warn("wiki compile unexpected failure section={} type={} message={}",
                        section.sectionLabel(), ex.getClass().getSimpleName(), ex.getMessage());
                return Outcome.failed("编译异常: " + ex.getClass().getSimpleName(), promptTokens,
                        completionTokens, calls);
            }
        }
        return Outcome.skipped("LLM 输出无法解析为规定 JSON（已重试 " + properties.getParseRetries()
                + " 次）: " + lastParseFailure, promptTokens, completionTokens, calls);
    }

    private String userPrompt(WikiSection section) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("文档：").append(nullSafe(section.docTitle())).append('\n');
        prompt.append("小节路径：").append(section.sectionLabel()).append('\n');
        prompt.append("材料（共 ").append(section.chunks().size()).append(" 个片段）：\n");
        for (PageSourceChunk chunk : section.chunks()) {
            prompt.append("\n[chunkId=").append(chunk.chunkId());
            if (chunk.pageNo() != null) {
                prompt.append(" page=").append(chunk.pageNo());
                if (chunk.pageEnd() != null && chunk.pageEnd() > chunk.pageNo()) {
                    prompt.append('-').append(chunk.pageEnd());
                }
            }
            prompt.append("]\n").append(nullSafe(chunk.content())).append('\n');
        }
        prompt.append("\n请只依据以上材料输出规定字段的 JSON。");
        return prompt.toString();
    }

    private WikiPageContent parse(WikiSection section, String raw) {
        String json = extractJsonObject(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException ex) {
            throw new DeepSeekClient.LlmOutputException("JSON 语法错误: " + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new DeepSeekClient.LlmOutputException("输出不是 JSON 对象");
        }
        String title = text(root, "title");
        if (title == null) {
            throw new DeepSeekClient.LlmOutputException("缺少 title");
        }
        String summary = text(root, "summary");
        if (summary == null) {
            throw new DeepSeekClient.LlmOutputException("缺少 summary");
        }
        List<String> keyPoints = strings(root, "key_points", 30, 300);
        List<String> crossRefs = strings(root, "cross_refs", 20, 40);
        List<UUID> sources = sources(section, strings(root, "sources", 100, 80));

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", title);
        canonical.put("summary", summary);
        canonical.put("key_points", keyPoints);
        canonical.put("cross_refs", crossRefs);
        canonical.put("sources", sources.stream().map(UUID::toString).toList());
        String body;
        try {
            body = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(canonical);
        } catch (IOException ex) {
            throw new IllegalStateException("canonical wiki JSON serialization failed", ex);
        }
        int tokenCount = tokenCounter.count(body);
        return new WikiPageContent(title, summary, keyPoints, crossRefs, sources, body, tokenCount);
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) {
            throw new DeepSeekClient.LlmOutputException("响应为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new DeepSeekClient.LlmOutputException("未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static List<String> strings(JsonNode root, String field, int maxItems, int maxChars) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > maxChars) {
                value = value.substring(0, maxChars);
            }
            values.add(value);
            if (values.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /**
     * Keeps only ids that were really supplied for this section. A model that invents ids must not
     * be able to point a page at an unrelated chunk; an empty list falls back to all supplied ids.
     */
    private static List<UUID> sources(WikiSection section, List<String> claimed) {
        Set<UUID> allowed = new LinkedHashSet<>();
        for (var chunk : section.chunks()) {
            allowed.add(chunk.chunkId());
        }
        List<UUID> resolved = new ArrayList<>();
        for (String raw : claimed) {
            try {
                UUID id = UUID.fromString(raw.trim());
                if (allowed.contains(id) && !resolved.contains(id)) {
                    resolved.add(id);
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: ignore instead of failing the whole page over provenance metadata.
            }
        }
        if (resolved.isEmpty()) {
            return List.copyOf(allowed);
        }
        return List.copyOf(resolved);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Page content ready for embedding/persistence plus per-section usage accounting. */
    public record WikiPageContent(
            String title,
            String summary,
            List<String> keyPoints,
            List<String> crossRefs,
            List<UUID> sourceChunkIds,
            String body,
            int tokenCount) {

        /** The text that is embedded: title + summary + key points (Phase 11 spec). */
        public String embeddingText() {
            return title + "\n" + summary + "\n" + String.join("\n", keyPoints);
        }
    }

    public enum Status {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    public record Outcome(
            Status status,
            WikiPageContent page,
            String reason,
            long promptTokens,
            long completionTokens,
            int llmCalls) {

        static Outcome success(WikiPageContent page, long promptTokens, long completionTokens,
                               int llmCalls) {
            return new Outcome(Status.SUCCESS, page, null, promptTokens, completionTokens, llmCalls);
        }

        static Outcome skipped(String reason, long promptTokens, long completionTokens, int llmCalls) {
            return new Outcome(Status.SKIPPED, null, reason, promptTokens, completionTokens, llmCalls);
        }

        static Outcome failed(String reason, long promptTokens, long completionTokens, int llmCalls) {
            return new Outcome(Status.FAILED, null, reason, promptTokens, completionTokens, llmCalls);
        }
    }
}
