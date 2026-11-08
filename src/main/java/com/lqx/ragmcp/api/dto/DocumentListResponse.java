package com.lqx.ragmcp.api.dto;

import java.util.List;

public record DocumentListResponse(List<DocumentSummaryResponse> items, long total, int limit, int offset) {
}
