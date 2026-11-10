package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/admin/pages/compile-wiki body.
 *
 * <p>{@code docIds} is optional: omitted/empty compiles every document. {@code concurrency} is
 * optional and clamped to 1..16; it defaults to {@code app.wiki.concurrency}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageCompileWikiRequest(List<String> docIds, Integer concurrency) {
}
