package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/admin/pages/rebuild body. {@code docIds} is optional: empty/omitted rebuilds every
 * document. Unknown fields are ignored for forward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageRebuildRequest(List<String> docIds) {
}
