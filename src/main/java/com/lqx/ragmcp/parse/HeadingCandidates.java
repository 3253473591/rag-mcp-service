package com.lqx.ragmcp.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heading candidate rules for PDF text (docs/11-retrieval-and-eval-spec.md 1.2).
 *
 * <p>The old parser treated every line starting with a number as a heading. In double-column
 * journal PDFs that produced clause paths such as {@code 90 mmHg}, {@code 3 d、至少} and
 * {@code 20 g、川牛膝}. This class keeps the two legitimate heading families and rejects
 * measurement/table fragments:</p>
 *
 * <ul>
 *   <li>Chinese clauses: {@code 第X章/节/条}, with Chinese or Arabic numbers, optional spaces
 *       and full-width digits;</li>
 *   <li>numeric sections: {@code 4.2.1 诊断标准}, where the title is not a unit/table fragment.</li>
 * </ul>
 */
public final class HeadingCandidates {

    private static final String CN_NUMBER = "[一二三四五六七八九十百千零〇两0-9０-９]+";
    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "^第[\\s\\u3000]*" + CN_NUMBER + "[\\s\\u3000]*[章节条]");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)[\\s\\u3000]+(.+)$");
    private static final Pattern ASCII_UNIT = Pattern.compile(
            "^(?:mmHg|kPa|cm|mm|min|sec|h|d|g|kg|mg|ml|ml|L|%)(?=\\s|\\u3000|\\*|、|，|,|。|；|;|\\)|）|（|\\d|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CJK_UNIT = Pattern.compile(
            "^(?:次|小时|分钟|秒|个|条|项|岁|倍|万|亿|元|米|公里|片|粒"
                    + "|周(?:内|后|前|以上|以下)"
                    + "|月(?:内|后|前|底|初|中|末)"
                    + "|年(?:内|后|前|底|初|中|末|度)"
                    + "|天(?:内|后|前|以))");
    private static final Pattern NUMBER_THEN_UNIT = Pattern.compile("^\\d+(?:\\.\\d+)?\\s*");
    private static final Pattern TABLE_DOSAGE = Pattern.compile(
            "(?i)(?:^|\\s)(?:qd|bid|tid|qid|mg|mmol|μmol)(?:\\s|$|[，,。；;])");
    private static final Pattern WORD = Pattern.compile("[A-Za-z\\u4e00-\\u9fff]");

    private HeadingCandidates() {
    }

    public static boolean isHeading(String text) {
        return isArticleHeading(text) || isNumberedHeading(text);
    }

    /**
     * True for a real Chinese clause heading ({@code 第X条}) at the start of a line. Body text
     * that merely mentions a clause in the middle does not match because the pattern is anchored.
     */
    public static boolean isArticleHeading(String text) {
        String value = trim(text);
        return value != null && ARTICLE_HEADING.matcher(value).find();
    }

    /** True for a numeric section heading such as {@code 4.2.1 诊断标准}. */
    public static boolean isNumberedHeading(String text) {
        String value = trim(text);
        if (value == null || value.length() > 100) {
            return false;
        }
        if (endsWithSentencePunctuation(value)) {
            return false;
        }
        if (value.indexOf('|') >= 0 || value.indexOf('=') >= 0) {
            return false;
        }
        Matcher matcher = NUMBERED_HEADING.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        String prefix = matcher.group(1);
        // A three-digit bare number is a page number, not a section id (e.g. journal page 842).
        if (prefix.indexOf('.') < 0 && prefix.length() > 2) {
            return false;
        }
        String title = matcher.group(2).strip();
        boolean numberThenUnit = false;
        Matcher number = NUMBER_THEN_UNIT.matcher(title);
        if (number.find()) {
            numberThenUnit = startsWithUnit(title.substring(number.end()));
        }
        if (title.isEmpty() || startsWithUnit(title) || numberThenUnit
                || TABLE_DOSAGE.matcher(value).find()) {
            return false;
        }
        return WORD.matcher(title).find();
    }

    /**
     * True when {@code text} starts with a measurement unit. Used by {@link ClauseIds} so
     * values such as {@code 90 mmHg} never become clause ids.
     */
    static boolean startsWithUnit(String text) {
        String value = trim(text);
        if (value == null) {
            return false;
        }
        return ASCII_UNIT.matcher(value).find() || CJK_UNIT.matcher(value).find();
    }

    private static boolean endsWithSentencePunctuation(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；' || last == '.'
                || last == '!' || last == '?' || last == ';' || last == '，' || last == ',';
    }

    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
