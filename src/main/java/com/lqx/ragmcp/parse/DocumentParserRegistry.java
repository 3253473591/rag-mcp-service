package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentParserRegistry {

    private final Map<SourceType, DocumentParser> parsers = new EnumMap<>(SourceType.class);

    public DocumentParserRegistry(List<DocumentParser> parserList) {
        for (DocumentParser parser : parserList) {
            parsers.put(parser.sourceType(), parser);
        }
    }

    public DocumentParser parserFor(SourceType sourceType) {
        DocumentParser parser = parsers.get(sourceType);
        if (parser == null) {
            throw ApiException.parseFailed("没有可用的解析器: " + sourceType);
        }
        return parser;
    }
}
