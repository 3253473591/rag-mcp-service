package com.lqx.ragmcp.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** R3 heading-candidate cleaning: measurements/table fragments must not become headings. */
class HeadingCandidatesTest {

    @Test
    void recognizesChineseArticleHeadings() {
        assertThat(HeadingCandidates.isHeading("第五条 企业建立与实施有效的内部控制，应当包括下列要素："))
                .isTrue();
        assertThat(HeadingCandidates.isHeading("第 五 条 企业建立与实施有效的内部控制，应当包括下列要素："))
                .isTrue();
        assertThat(HeadingCandidates.isHeading("第22条 企业应当建立反舞弊机制。"))
                .isTrue();
        assertThat(HeadingCandidates.isHeading("第一章 总 则")).isTrue();
        assertThat(HeadingCandidates.isHeading("第二节 风险评估")).isTrue();
    }

    @Test
    void rejectsArticleMentionsInsideBodyText() {
        assertThat(HeadingCandidates.isHeading("根据第五条规定，企业应当建立内部控制。")).isFalse();
        assertThat(HeadingCandidates.isHeading("本条所称第五条，是指内部控制要素。")).isFalse();
    }

    @Test
    void recognizesNumericSectionHeadings() {
        assertThat(HeadingCandidates.isHeading("4.2.1 诊断标准")).isTrue();
        assertThat(HeadingCandidates.isHeading("8.1 随访频率")).isTrue();
        assertThat(HeadingCandidates.isHeading("1 基层高血压诊疗管理流程")).isTrue();
        assertThat(HeadingCandidates.isHeading("5.4.3.1 <80 岁、无合并症的高血压药物治疗方案"))
                .isTrue();
        assertThat(HeadingCandidates.isHeading("5.4.6.1 血压≥ 180/110 mmHg，不伴心、脑、肾急性并发症的临床症状"))
                .isTrue();
    }

    @Test
    void rejectsMeasurementAndTableFragments() {
        assertThat(HeadingCandidates.isHeading("90 mmHg")).isFalse();
        assertThat(HeadingCandidates.isHeading("90 mmHg*")).isFalse();
        assertThat(HeadingCandidates.isHeading("140/90 mmHg")).isFalse();
        assertThat(HeadingCandidates.isHeading("2.5 cm（约两横指")).isFalse();
        assertThat(HeadingCandidates.isHeading("3 d、至少 12 个测量值")).isFalse();
        assertThat(HeadingCandidates.isHeading("20 g、川牛膝")).isFalse();
        assertThat(HeadingCandidates.isHeading("1 min，取两次的平均值")).isFalse();
        assertThat(HeadingCandidates.isHeading("4 周内非同日 3 次规范测量")).isFalse();
        assertThat(HeadingCandidates.isHeading("120 mmHg 和舒张压 80 mmHg")).isFalse();
        assertThat(HeadingCandidates.isHeading("1 4 mg qd 合并糖尿病")).isFalse();
        assertThat(HeadingCandidates.isHeading("2 25 mg bid 心绞痛")).isFalse();
        assertThat(HeadingCandidates.isHeading("842 中国循环杂志 2025 年 9 月")).isFalse();
    }

    @Test
    void rejectsSentenceLikeNumericLines() {
        assertThat(HeadingCandidates.isHeading("4 次测量值的平均值用于高血压的诊断。")).isFalse();
        assertThat(HeadingCandidates.isHeading("2 诊室血压分类及其定义")).isTrue();
    }

    @Test
    void blankInputIsNotAHeading() {
        assertThat(HeadingCandidates.isHeading(null)).isFalse();
        assertThat(HeadingCandidates.isHeading("  ")).isFalse();
    }
}
