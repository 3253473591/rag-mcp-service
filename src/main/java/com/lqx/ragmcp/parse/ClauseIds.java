package com.lqx.ragmcp.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clause id extraction (docs/11-retrieval-and-eval-spec.md 1.6).
 *
 * <p>Two forms are supported: numeric section ids such as {@code 4.2.1}, and Chinese article
 * ids such as {@code 第五条} / {@code 第 12 条}. Only a prefix of a heading line is considered;
 * a {@code 第X条} mentioned in the middle of body text is never extracted. Measurement lines
 * such as {@code 90 mmHg} are not clause ids.</p>
 */
public final class ClauseIds {

    private static final String CN_NUMBER = "[一二三四五六七八九十百千零〇两0-9０-９]+";
    private static final Pattern ARTICLE_ID = Pattern.compile(
            "^第[\\s\\u3000]*(" + CN_NUMBER + ")[\\s\\u3000]*([章节条])");
    private static final Pattern NUMERIC_ID = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)(?=\\s|\\u3000|、|\\.|$)");

    private ClauseIds() {
    }

    public static String extract(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher article = ARTICLE_ID.matcher(value);
        if (article.find()) {
            return "第" + normalizeDigits(article.group(1)) + article.group(2);
        }
        Matcher numeric = NUMERIC_ID.matcher(value);
        if (numeric.find()) {
            String rest = value.substring(numeric.end()).strip();
            // 90 mmHg / 3 d / 2.5 cm / 1 min are table or measurement fragments, not headings.
            if (rest.isEmpty() || HeadingCandidates.startsWithUnit(rest)) {
                return null;
            }
            return numeric.group(1);
        }
        return null;
    }

    private static String normalizeDigits(String digits) {
        StringBuilder normalized = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            char ch = digits.charAt(i);
            if (ch >= '０' && ch <= '９') {
                normalized.append((char) ('0' + (ch - '０')));
            } else {
                normalized.append(ch);
            }
        }
        return normalized.toString();
    }
}
