package com.lqx.ragmcp.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Token counting with jtokkit cl100k (docs/11-retrieval-and-eval-spec.md 1.5).
 * This is an approximation of bge-m3 tokenization and is documented as such in README.
 */
@Component
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** Returns at most {@code maxTokens} trailing tokens of the text. */
    public String tail(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        IntArrayList tail = new IntArrayList(maxTokens);
        for (int i = tokens.size() - maxTokens; i < tokens.size(); i++) {
            tail.add(tokens.get(i));
        }
        return encoding.decode(tail);
    }

    /** Hard-splits text into pieces of at most {@code maxTokens} tokens. */
    public List<String> split(String text, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return pieces;
        }
        IntArrayList tokens = encoding.encode(text);
        for (int start = 0; start < tokens.size(); start += maxTokens) {
            int end = Math.min(start + maxTokens, tokens.size());
            IntArrayList slice = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                slice.add(tokens.get(i));
            }
            pieces.add(encoding.decode(slice));
        }
        return pieces;
    }
}
