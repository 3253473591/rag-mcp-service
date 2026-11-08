package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.parse.Block;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every requested chunking strategy over the same parsed blocks and produces
 * indexed, token-counted chunks ready for embedding.
 */
@Service
public class ChunkingService {

    private final Map<ChunkStrategy, Chunker> chunkers = new EnumMap<>(ChunkStrategy.class);
    private final TokenCounter tokenCounter;

    public ChunkingService(List<Chunker> chunkerList, TokenCounter tokenCounter) {
        for (Chunker chunker : chunkerList) {
            chunkers.put(chunker.strategy(), chunker);
        }
        this.tokenCounter = tokenCounter;
    }

    public Map<ChunkStrategy, List<PreparedChunk>> chunk(List<Block> blocks, List<ChunkStrategy> strategies) {
        Map<ChunkStrategy, List<PreparedChunk>> result = new EnumMap<>(ChunkStrategy.class);
        int total = 0;
        for (ChunkStrategy strategy : strategies) {
            Chunker chunker = chunkers.get(strategy);
            if (chunker == null) {
                throw ApiException.validation("没有可用的切分策略: " + strategy.dbValue());
            }
            List<PreparedChunk> prepared = new ArrayList<>();
            int index = 0;
            for (ChunkDraft draft : chunker.chunk(blocks)) {
                String content = draft.content() == null ? "" : draft.content().strip();
                if (content.isEmpty()) {
                    continue;
                }
                int tokens = tokenCounter.count(content);
                if (tokens <= 0) {
                    continue;
                }
                prepared.add(new PreparedChunk(strategy, index++, content, tokens,
                        draft.clauseId(), draft.clausePath(), draft.pageNo(), draft.table()));
            }
            result.put(strategy, prepared);
            total += prepared.size();
        }
        if (total == 0) {
            throw ApiException.parseFailed("切分后没有有效内容");
        }
        return result;
    }
}
