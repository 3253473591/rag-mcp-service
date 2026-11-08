package com.lqx.ragmcp.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and validates a JSONL QA dataset (docs/11-retrieval-and-eval-spec.md 4.1).
 *
 * <p>Validation is deliberately strict: duplicate ids, blank questions, missing gold docKey,
 * malformed page ranges and malformed per-question asOf values fail the run instead of silently
 * producing a misleading metric.</p>
 */
public record EvalDataset(List<EvalQaItem> items) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EvalDataset {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("QA dataset is empty");
        }
        items = List.copyOf(items);
        Set<String> ids = new HashSet<>();
        for (EvalQaItem item : items) {
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate QA id: " + item.id());
            }
        }
    }

    public static EvalDataset load(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader, path.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read QA dataset: " + path, ex);
        }
    }

    public static EvalDataset load(Reader reader) {
        return load(reader, "qa.jsonl");
    }

    public static EvalDataset load(Reader reader, String source) {
        List<EvalQaItem> items = new ArrayList<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            int lineNo = 0;
            while ((line = buffered.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                final EvalQaItem item;
                try {
                    item = MAPPER.readValue(line, EvalQaItem.class);
                } catch (IOException ex) {
                    throw new IllegalArgumentException(
                            "invalid QA JSON in " + source + " line " + lineNo + ": " + ex.getMessage(), ex);
                }
                validate(item, source, lineNo);
                items.add(item);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read QA dataset: " + source, ex);
        }
        return new EvalDataset(items);
    }

    public static EvalDataset of(List<EvalQaItem> items) {
        if (items != null) {
            int lineNo = 0;
            for (EvalQaItem item : items) {
                lineNo++;
                validate(item, "in-memory dataset", lineNo);
            }
        }
        return new EvalDataset(items);
    }

    public int answerableCount() {
        return (int) items.stream().filter(EvalQaItem::answerable).count();
    }

    public int unanswerableCount() {
        return items.size() - answerableCount();
    }

    private static void validate(EvalQaItem item, String source, int lineNo) {
        if (item == null) {
            throw new IllegalArgumentException("null QA item in " + source + " line " + lineNo);
        }
        if (item.id() == null || item.id().isBlank()) {
            throw new IllegalArgumentException("QA item id is blank in " + source + " line " + lineNo);
        }
        if (item.question() == null || item.question().isBlank()) {
            throw new IllegalArgumentException("QA item " + item.id() + " has a blank question");
        }
        if (item.asOf() != null && !item.asOf().isBlank()) {
            try {
                LocalDate.parse(item.asOf().trim());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "QA item " + item.id() + " has invalid asOf: " + item.asOf());
            }
        }
        if (!item.answerable()) {
            return;
        }
        EvalGold gold = item.gold();
        if (gold == null) {
            throw new IllegalArgumentException("answerable QA item " + item.id() + " has no gold");
        }
        if (gold.docKey() == null || gold.docKey().isBlank()) {
            throw new IllegalArgumentException("answerable QA item " + item.id() + " has no gold.docKey");
        }
        if (gold.clauseId() != null && gold.clauseId().isBlank()) {
            throw new IllegalArgumentException(
                    "answerable QA item " + item.id() + " has a blank gold.clauseId");
        }
        List<Integer> pageRange = gold.pageRange();
        if (pageRange != null) {
            if (pageRange.size() != 2) {
                throw new IllegalArgumentException(
                        "answerable QA item " + item.id() + " gold.pageRange must have two values");
            }
            Integer start = pageRange.get(0);
            Integer end = pageRange.get(1);
            if (start == null || end == null || start < 1 || end < start) {
                throw new IllegalArgumentException(
                        "answerable QA item " + item.id() + " has invalid gold.pageRange " + pageRange);
            }
        }
    }

}
