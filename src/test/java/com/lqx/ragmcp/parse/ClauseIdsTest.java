package com.lqx.ragmcp.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Clause ids from docs/11-retrieval-and-eval-spec.md 1.6, including Chinese articles. */
class ClauseIdsTest {

    @Test
    void extractsChineseArticleIdsInStableReadableForm() {
        assertThat(ClauseIds.extract("第五条 企业建立与实施有效的内部控制，应当包括下列要素："))
                .isEqualTo("第五条");
        assertThat(ClauseIds.extract("第四十六条 企业应当组织协调内部控制的建立实施。"))
                .isEqualTo("第四十六条");
        assertThat(ClauseIds.extract("第十二条 企业应当建立内部控制实施的激励约束机制。"))
                .isEqualTo("第十二条");
    }

    @Test
    void normalizesSpacesAndFullWidthArabicDigits() {
        assertThat(ClauseIds.extract("第 五 条 企业应当建立有效的内部控制。"))
                .isEqualTo("第五条");
        assertThat(ClauseIds.extract("第１２条 企业应当建立有效的内部控制。"))
                .isEqualTo("第12条");
        assertThat(ClauseIds.extract("第 12 条 企业应当建立有效的内部控制。"))
                .isEqualTo("第12条");
    }

    @Test
    void extractsChineseChapterAndSectionIds() {
        assertThat(ClauseIds.extract("第一章 总 则")).isEqualTo("第一章");
        assertThat(ClauseIds.extract("第二节 风险评估")).isEqualTo("第二节");
    }

    @Test
    void keepsNumericClauseIdsForNumericHeadings() {
        assertThat(ClauseIds.extract("4.2.1 诊断标准")).isEqualTo("4.2.1");
        assertThat(ClauseIds.extract("8.1 随访频率")).isEqualTo("8.1");
    }

    @Test
    void doesNotExtractClauseIdsFromTheMiddleOfBodyText() {
        assertThat(ClauseIds.extract("根据第五条规定，企业应当建立内部控制。")).isNull();
        assertThat(ClauseIds.extract("See section 4.2.1 for details.")).isNull();
        assertThat(ClauseIds.extract("90 mmHg")).isNull();
    }

    @Test
    void nullAndBlankReturnNull() {
        assertThat(ClauseIds.extract(null)).isNull();
        assertThat(ClauseIds.extract("   ")).isNull();
    }
}
