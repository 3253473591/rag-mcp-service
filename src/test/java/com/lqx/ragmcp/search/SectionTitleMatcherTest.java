package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** T-UNIT-SECTION-01/02: query normalization and the leaf-heading boost (Phase 6c). */
class SectionTitleMatcherTest {

    @Test
    void tUnitSection01_normalizationRemovesQuestionNoiseAndPunctuation() {
        assertThat(SectionTitleMatcher.normalizeQuestion("高血压的诊断流程是什么？"))
                .isEqualTo("高血压诊断流程");
        assertThat(SectionTitleMatcher.normalizeQuestion("诊室血压的诊断标准是多少？"))
                .isEqualTo("诊室血压诊断标准");
        assertThat(SectionTitleMatcher.normalizeQuestion("2024 版指南中的随访频率是多少？"))
                .isEqualTo("2024版指南中随访频率");
    }

    @Test
    void tUnitSection02_leafTitleNamedByQueryGetsSmallBoostOnly() {
        String normalized = SectionTitleMatcher.normalizeQuestion("高血压的诊断流程是什么？");

        assertThat(SectionTitleMatcher.boost(
                normalized, "4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程"))
                .isCloseTo(0.002d, within(1e-12));
        // 4.3 has no leaf title named by the query, and its parent text is not considered.
        assertThat(SectionTitleMatcher.boost(
                normalized, "4  高血压诊断与评估 > 4.3  初诊评估"))
                .isZero();
        // A parent heading is not a leaf title match.
        assertThat(SectionTitleMatcher.boost(
                "诊室血压诊断标准", "4  高血压诊断与评估 > 4.2  高血压诊断标准"))
                .isZero();
        assertThat(SectionTitleMatcher.boost(
                "无合并症80岁以下高血压患者选择药物治疗方案",
                "5  高血压治疗 > 5.4  降压药物治疗 > 5.4.3  药物治疗方案"))
                .isCloseTo(0.003d, within(1e-12));
    }

    @Test
    void tUnitSection02_longArticleHeadingAndShortTitleAreIgnored() {
        assertThat(SectionTitleMatcher.boost(
                "企业应当开展风险评估",
                "第一章 总 则 > 第四条 企业建立与实施内部控制，应当遵循下列原则："))
                .isZero();
        assertThat(SectionTitleMatcher.boost(
                "转诊", "7  转诊"))
                .isZero();
        assertThat(SectionTitleMatcher.boost("", null)).isZero();
    }
}
