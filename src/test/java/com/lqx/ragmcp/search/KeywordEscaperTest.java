package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordEscaperTest {

    @Test
    void escapesBackslashPercentAndUnderscoreBeforeWrapping() {
        assertThat(KeywordEscaper.escapeLike("a\\b%c_d"))
                .isEqualTo("a\\\\b\\%c\\_d");
        assertThat(KeywordEscaper.toPattern("100%_ok"))
                .isEqualTo("%100\\%\\_ok%");
    }

    @Test
    void handlesNullAsEmpty() {
        assertThat(KeywordEscaper.escapeLike(null)).isEmpty();
        assertThat(KeywordEscaper.toPattern(null)).isEqualTo("%%");
    }

    @Test
    void keywordTextIsCappedAtTwoHundredCharacters() {
        String query = "中".repeat(250);

        assertThat(KeywordEscaper.keywordText(query, 200)).hasSize(200);
        assertThat(KeywordEscaper.keywordText("short", 200)).isEqualTo("short");
    }
}
