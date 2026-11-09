package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.parse.Block;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-window chunking (docs/11-retrieval-and-eval-spec.md 1.5): target 600 tokens,
 * overlap 80 tokens, paragraph boundaries first, sentence boundaries for oversized
 * paragraphs. Tables are never cut mid-row: oversized tables are split by rows with the
 * header row repeated.
 *
 * <p>Phase 6 adds two pieces of metadata without changing the 600/80 window semantics:
 * each chunk inherits the dominant heading section it covers ({@code clause_id} /
 * {@code clause_path}), and records its full page span ({@code page_no}..{@code page_end}).
 * The dominant-section rule is intentionally conservative: when a fixed window crosses
 * several headings, the section contributing the most tokens wins.</p>
 */
@Component
public class FixedWindowChunker implements Chunker {

    public static final int TARGET_TOKENS = 600;
    public static final int OVERLAP_TOKENS = 80;

    private final TokenCounter counter;
    private final int targetTokens;
    private final int overlapTokens;

    @org.springframework.beans.factory.annotation.Autowired
    public FixedWindowChunker(TokenCounter counter) {
        this(counter, TARGET_TOKENS, OVERLAP_TOKENS);
    }

    public FixedWindowChunker(TokenCounter counter, int targetTokens, int overlapTokens) {
        this.counter = counter;
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED;
    }

    @Override
    public List<ChunkDraft> chunk(List<Block> blocks) {
        List<ChunkDraft> result = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return result;
        }
        List<Piece> pieces = new ArrayList<>();
        String currentClauseId = null;
        String currentClausePath = null;
        for (Block block : blocks) {
            if (block == null || block.text() == null || block.text().isBlank()) {
                continue;
            }
            if (block.isHeading()) {
                currentClauseId = block.clauseId();
                currentClausePath = block.clausePath();
            }
            if (block.table()) {
                pieces.add(new Piece(block.text(), block, true, currentClauseId, currentClausePath));
            } else {
                pieces.addAll(splitBlock(block, currentClauseId, currentClausePath));
            }
        }

        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        Integer currentPage = null;
        Integer currentPageEnd = null;
        boolean currentHasNewContent = false;
        Map<Section, Integer> sections = new LinkedHashMap<>();

        for (Piece piece : pieces) {
            if (piece.table()) {
                if (currentHasNewContent && currentTokens > 0) {
                    result.add(draft(current.toString(), currentPage, currentPageEnd, sections));
                }
                current.setLength(0);
                currentTokens = 0;
                currentPage = null;
                currentPageEnd = null;
                currentHasNewContent = false;
                sections.clear();
                result.addAll(splitTable(piece));
                continue;
            }
            int pieceTokens = Math.max(1, counter.count(piece.text()));
            if (currentTokens > 0 && currentTokens + pieceTokens > targetTokens) {
                String flushed = current.toString();
                if (currentHasNewContent) {
                    result.add(draft(flushed, currentPage, currentPageEnd, sections));
                }
                Section overlapSection = dominantSection(sections);
                String overlap = counter.tail(flushed, overlapTokens).trim();
                current.setLength(0);
                currentTokens = 0;
                currentHasNewContent = false;
                sections.clear();
                if (!overlap.isEmpty() && counter.count(overlap) + pieceTokens <= targetTokens) {
                    current.append(overlap);
                    currentTokens = counter.count(overlap);
                    currentPage = currentPageEnd;
                    currentPageEnd = currentPage;
                    if (overlapSection != null) {
                        sections.put(overlapSection, currentTokens);
                    }
                } else {
                    currentPage = null;
                    currentPageEnd = null;
                }
            }
            if (currentTokens == 0) {
                currentPage = piece.block().pageNo();
                currentPageEnd = currentPage;
            } else {
                currentPageEnd = maxPage(currentPageEnd, piece.block().pageNo());
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(piece.text());
            currentTokens += pieceTokens;
            addSection(sections, piece, pieceTokens);
            currentHasNewContent = true;
        }
        if (currentHasNewContent && currentTokens > 0) {
            result.add(draft(current.toString(), currentPage, currentPageEnd, sections));
        }
        return result;
    }

    private List<Piece> splitBlock(Block block, String clauseId, String clausePath) {
        List<Piece> pieces = new ArrayList<>();
        String text = block.text().trim();
        if (text.isEmpty()) {
            return pieces;
        }
        if (counter.count(text) <= targetTokens) {
            pieces.add(new Piece(text, block, false, clauseId, clausePath));
            return pieces;
        }
        // Oversized paragraph: feed sentences one by one so the window can pack them and
        // keep a meaningful overlap. A single oversized sentence is hard-split by tokens.
        for (String sentence : splitSentences(text)) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (counter.count(trimmed) > targetTokens) {
                for (String hardPiece : counter.split(trimmed, targetTokens)) {
                    String hardTrimmed = hardPiece.strip();
                    if (!hardTrimmed.isEmpty()) {
                        pieces.add(new Piece(hardTrimmed, block, false, clauseId, clausePath));
                    }
                }
            } else {
                pieces.add(new Piece(trimmed, block, false, clauseId, clausePath));
            }
        }
        return pieces;
    }

    private List<ChunkDraft> splitTable(Piece tablePiece) {
        List<ChunkDraft> result = new ArrayList<>();
        Block table = tablePiece.block();
        String text = table.text().trim();
        if (text.isEmpty()) {
            return result;
        }
        if (counter.count(text) <= targetTokens) {
            result.add(new ChunkDraft(text, tablePiece.clauseId(), tablePiece.clausePath(),
                    table.pageNo(), table.pageNo(), true));
            return result;
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        if (lines.size() <= 1) {
            result.add(new ChunkDraft(text, tablePiece.clauseId(), tablePiece.clausePath(),
                    table.pageNo(), table.pageNo(), true));
            return result;
        }
        String header = lines.get(0);
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            String row = lines.get(i);
            String candidate = body.length() == 0 ? header + "\n" + row : header + "\n" + body + "\n" + row;
            if (body.length() > 0 && counter.count(candidate) > targetTokens) {
                result.add(new ChunkDraft(header + "\n" + body, tablePiece.clauseId(),
                        tablePiece.clausePath(), table.pageNo(), table.pageNo(), true));
                body.setLength(0);
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(row);
        }
        if (body.length() > 0) {
            result.add(new ChunkDraft(header + "\n" + body, tablePiece.clauseId(),
                    tablePiece.clausePath(), table.pageNo(), table.pageNo(), true));
        }
        return result;
    }

    private static ChunkDraft draft(String text,
                                    Integer pageNo,
                                    Integer pageEnd,
                                    Map<Section, Integer> sections) {
        Section section = dominantSection(sections);
        return new ChunkDraft(text.strip(),
                section == null ? null : section.clauseId(),
                section == null ? null : section.clausePath(),
                pageNo, pageEnd, false);
    }

    private static void addSection(Map<Section, Integer> sections, Piece piece, int tokens) {
        Section section = new Section(piece.clauseId(), piece.clausePath());
        sections.merge(section, tokens, Integer::sum);
    }

    private static Section dominantSection(Map<Section, Integer> sections) {
        Section best = null;
        int bestTokens = -1;
        for (Map.Entry<Section, Integer> entry : sections.entrySet()) {
            if (entry.getValue() > bestTokens) {
                best = entry.getKey();
                bestTokens = entry.getValue();
            }
        }
        return best;
    }

    private static Integer maxPage(Integer current, Integer candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return Math.max(current, candidate);
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean boundary = ch == '。' || ch == '！' || ch == '？' || ch == '；'
                    || ch == '!' || ch == '?' || ch == ';' || ch == '\n';
            if (boundary) {
                sentences.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }
        return sentences;
    }

    /** Unit fed into the window: either a text piece or a whole table. */
    private record Piece(String text,
                         Block block,
                         boolean table,
                         String clauseId,
                         String clausePath) {
    }

    private record Section(String clauseId, String clausePath) {
    }
}
