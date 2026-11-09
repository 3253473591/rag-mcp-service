package com.lqx.ragmcp.search;

import org.springframework.stereotype.Component;

/**
 * System-side fallback for an empty answer text. This is <b>not</b> a model-facing option: the
 * messages are constants owned by the service and cannot be rewritten by a caller or a prompt.
 *
 * <p>The MCP {@code search_knowledge} response assembly calls {@link #fallback(String,
 * AbstainReason)} today with the retrieval message (no generation layer exists yet). When a
 * generation layer is added, its raw text should be passed as {@code upstreamText}; if the model
 * returns null/blank, this component supplies the deterministic abstain text instead of silently
 * returning an empty answer.</p>
 */
@Component
public class AnswerFallback {

    /** docs/02-api-contract.md 2.9: NO_MATCH must be an honest statement, not an empty reply. */
    public static final String NO_MATCH_MESSAGE =
            "未检索到足以回答该问题的资料。请补充更具体的信息，或联系管理员确认知识库是否已收录相关内容。";

    /** docs/02-api-contract.md 2.9: TOO_NOISY must explain that the evidence was not discriminative. */
    public static final String TOO_NOISY_MESSAGE =
            "检索到的内容噪声过多，无法判断。请缩小问题范围或提供更明确的条款、文档名称后重试。";

    /** Defensive text for an empty response that is not classified as an abstention. */
    public static final String GENERIC_EMPTY_MESSAGE =
            "本次检索未返回可用内容，请调整问题后重试或联系管理员。";

    /**
     * @param upstreamText text produced upstream (currently the retrieval message; later an LLM
     *                     answer). Returned unchanged when non-blank.
     * @param reason       abstention reason, or {@code null}/NONE for a generic empty response.
     * @return a guaranteed non-blank final text.
     */
    public String fallback(String upstreamText, AbstainReason reason) {
        if (upstreamText != null && !upstreamText.isBlank()) {
            return upstreamText;
        }
        return messageFor(reason);
    }

    public String messageFor(AbstainReason reason) {
        if (reason == null) {
            return GENERIC_EMPTY_MESSAGE;
        }
        return switch (reason) {
            case NO_MATCH -> NO_MATCH_MESSAGE;
            case TOO_NOISY -> TOO_NOISY_MESSAGE;
            case NONE -> GENERIC_EMPTY_MESSAGE;
        };
    }
}
