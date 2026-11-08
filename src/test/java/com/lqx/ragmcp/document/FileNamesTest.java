package com.lqx.ragmcp.document;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.chunk.ChunkStrategies;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileNamesTest {

    @Test
    void tUnitValid02_stripsClientPathAndKeepsBasename() {
        assertThat(FileNames.basename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.basename("..\\..\\windows\\win.ini")).isEqualTo("win.ini");
        assertThat(FileNames.basename("C:\\fakepath\\指南.pdf")).isEqualTo("指南.pdf");
        assertThat(FileNames.basename("/tmp/upload/a.docx")).isEqualTo("a.docx");
    }

    @Test
    void rejectsTraversalOnlyNames() {
        assertThatThrownBy(() -> FileNames.basename(".."))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
        assertThatThrownBy(() -> FileNames.basename("a/.."))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FileNames.basename(" "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void extensionAndTitleFromFilename() {
        assertThat(FileNames.extension("A.PDF")).isEqualTo("pdf");
        assertThat(FileNames.extension("noext")).isEmpty();
        assertThat(FileNames.titleFromFilename("2025 指南.pdf")).isEqualTo("2025 指南");
    }

    @Test
    void chunkStrategiesDefaultsDedupesAndValidates() {
        assertThat(ChunkStrategies.parse(List.of())).containsExactly(ChunkStrategy.HEADING);
        assertThat(ChunkStrategies.parse(List.of("fixed,heading")))
                .containsExactly(ChunkStrategy.FIXED, ChunkStrategy.HEADING);
        assertThat(ChunkStrategies.parse(List.of("FIXED", "heading", "fixed")))
                .containsExactly(ChunkStrategy.FIXED, ChunkStrategy.HEADING);
        assertThatThrownBy(() -> ChunkStrategies.parse(List.of("semantic")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
        assertThatThrownBy(() -> ChunkStrategies.parse(List.of("fixed,heading,semantic")))
                .isInstanceOf(ApiException.class);
    }
}
