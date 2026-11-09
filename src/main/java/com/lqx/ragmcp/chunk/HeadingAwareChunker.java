package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.parse.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Heading-aware chunking (docs/11-retrieval-and-eval-spec.md 1.5): sections are formed by
 * heading hierarchy; a section of at most 900 tokens becomes one chunk, longer sections
 * are recursively split with the 600/80 fixed window. Every chunk carries the section
 * clause id / clause path, and heading text is never a chunk on its own. Documents
 * without headings fall back to fixed windows.
 */
@Component
public class HeadingAwareChunker implements Chunker {

    public static final int MAX_SECTION_TOKENS = 900;

    private final TokenCounter counter;
    private final FixedWindowChunker fixedFallback;

    public HeadingAwareChunker(TokenCounter counter) {
        this.counter = counter;
        this.fixedFallback = new FixedWindowChunker(counter, FixedWindowChunker.TARGET_TOKENS,
                FixedWindowChunker.OVERLAP_TOKENS);
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.HEADING;
    }

    @Override
    public List<ChunkDraft> chunk(List<Block> blocks) {
        List<ChunkDraft> result = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return result;
        }
        boolean hasHeading = blocks.stream().anyMatch(Block::isHeading);
        if (!hasHeading) {
            return fixedFallback.chunk(blocks);
        }
        List<Section> sections = buildSections(blocks);
        if (sections.isEmpty()) {
            return fixedFallback.chunk(blocks);
        }
        for (Section section : sections) {
            if (section.blocks().isEmpty()) {
                continue;
            }
            int sectionTokens = section.blocks().stream()
                    .mapToInt(block -> counter.count(block.text()))
                    .sum();
            if (sectionTokens <= MAX_SECTION_TOKENS) {
                String content = joinBlocks(section.blocks());
                if (!content.isBlank()) {
                    result.add(new ChunkDraft(content, section.clauseId(), section.clausePath(),
                            firstPage(section.blocks()), lastPage(section.blocks()), false));
                }
            } else {
                for (ChunkDraft sub : fixedFallback.chunk(section.blocks())) {
                    result.add(new ChunkDraft(sub.content(), section.clauseId(), section.clausePath(),
                            sub.pageNo() != null ? sub.pageNo() : firstPage(section.blocks()),
                            sub.pageEnd() != null ? sub.pageEnd() : lastPage(section.blocks()),
                            sub.table()));
                }
            }
        }
        if (result.isEmpty()) {
            return fixedFallback.chunk(blocks);
        }
        return result;
    }

    private List<Section> buildSections(List<Block> blocks) {
        List<Section> sections = new ArrayList<>();
        String clauseId = null;
        String clausePath = null;
        List<Block> body = new ArrayList<>();
        for (Block block : blocks) {
            if (block.isHeading()) {
                if (!body.isEmpty()) {
                    sections.add(new Section(clauseId, clausePath, List.copyOf(body)));
                    body.clear();
                }
                clauseId = block.clauseId();
                clausePath = block.clausePath();
            } else if (!block.text().isBlank()) {
                body.add(block);
            }
        }
        if (!body.isEmpty()) {
            sections.add(new Section(clauseId, clausePath, List.copyOf(body)));
        }
        return sections;
    }

    private static String joinBlocks(List<Block> blocks) {
        StringBuilder builder = new StringBuilder();
        for (Block block : blocks) {
            if (block.text().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(block.text().strip());
        }
        return builder.toString().strip();
    }

    private static Integer firstPage(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.pageNo() != null) {
                return block.pageNo();
            }
        }
        return null;
    }

    private static Integer lastPage(List<Block> blocks) {
        Integer last = null;
        for (Block block : blocks) {
            if (block.pageNo() != null && (last == null || block.pageNo() > last)) {
                last = block.pageNo();
            }
        }
        return last;
    }

    private record Section(String clauseId, String clausePath, List<Block> blocks) {
    }
}
