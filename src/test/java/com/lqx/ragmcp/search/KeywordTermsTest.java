package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordTermsTest {

    @Test
    void chineseQuestionYieldsContentTermsWithoutQuestionNoise() {
        List<String> terms = KeywordTerms.extract("高血压的诊断标准是什么？", 8);

        assertThat(terms).contains("高血压", "诊断标准");
        assertThat(terms).noneMatch(term -> term.contains("是什么"));
        assertThat(terms).noneMatch(term -> term.contains("的"));
        assertThat(terms).allMatch(term -> term.length() >= 2);
    }

    @Test
    void keepsTheCleanedWholeQuestionForExactPhraseMatches() {
        List<String> terms = KeywordTerms.extract("高血压的诊断标准是什么？", 8);

        assertThat(terms).contains("高血压诊断标准");
    }

    @Test
    void trimsConnectorCharactersOnlyAtTermEdges() {
        List<String> terms = KeywordTerms.extract("公司年假有多少天？", 8);

        assertThat(terms).contains("公司年假");
        // 有 inside a word must not be stripped: 有效控制 stays intact.
        assertThat(KeywordTerms.extract("有效控制", 8)).containsExactly("有效控制");
    }

    @Test
    void englishQuestionDropsStopwordsAndShortWords() {
        List<String> terms = KeywordTerms.extract(
                "What is the blood pressure threshold for adults?", 8);

        assertThat(terms).contains("blood", "pressure", "threshold", "adults");
        assertThat(terms).doesNotContain("what", "is", "the", "for");
    }

    @Test
    void wildcardCharactersStayLiteralForEscaping() {
        List<String> terms = KeywordTerms.extract("100%_", 8);

        assertThat(terms).containsExactly("100%_");
    }

    @Test
    void unusableQueriesProduceNoTerms() {
        assertThat(KeywordTerms.extract(null, 8)).isEmpty();
        assertThat(KeywordTerms.extract("   ", 8)).isEmpty();
        assertThat(KeywordTerms.extract("q", 8)).isEmpty();
        assertThat(KeywordTerms.extract("高血压", 0)).isEmpty();
    }

    @Test
    void respectsTheTermCap() {
        List<String> terms = KeywordTerms.extract(
                "高血压 糖尿病 冠心病 脑卒中 肾病 视网膜病变 随访 药物 饮食 运动", 4);

        assertThat(terms).hasSize(4);
    }

    @Test
    void outputIsDeterministicAndDeduplicated() {
        List<String> first = KeywordTerms.extract("高血压患者的高血压管理", 8);
        List<String> second = KeywordTerms.extract("高血压患者的高血压管理", 8);

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotHaveDuplicates();
    }
}
