package com.lqx.ragmcp.api.dto;

/**
 * Inclusive page span of a page part. Both values are {@code null} when the parser had no page
 * information; {@code pageEnd} is never lower than {@code pageNo}.
 */
public record PageRange(Integer pageNo, Integer pageEnd) {
}
