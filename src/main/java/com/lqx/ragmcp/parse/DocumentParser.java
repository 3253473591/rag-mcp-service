package com.lqx.ragmcp.parse;

import java.util.List;

/**
 * Parses one file format into ordered blocks. Implementations throw
 * {@link com.lqx.ragmcp.api.error.ApiException} with ERR-PARSE_FAILED on failure.
 */
public interface DocumentParser {

    SourceType sourceType();

    List<Block> parse(byte[] content, String basename);
}
