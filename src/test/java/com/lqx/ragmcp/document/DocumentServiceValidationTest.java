package com.lqx.ragmcp.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.chunk.ChunkingService;
import com.lqx.ragmcp.config.UploadProperties;
import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.parse.DocumentParserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Upload/list/detail validation that does not need a database (docs/04-boundaries-and-errors.md 1).
 */
class DocumentServiceValidationTest {

    private DocumentRepository repository;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        DocumentParserRegistry registry = mock(DocumentParserRegistry.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        UploadProperties uploadProperties = new UploadProperties();
        service = new DocumentService(repository, registry, chunkingService, embeddingService,
                uploadProperties, transactionTemplate, new ObjectMapper());
    }

    @Test
    void tUnitValid01_limitMustBeBetween1And100() {
        assertThatThrownBy(() -> service.list(0, 0))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
        assertThatThrownBy(() -> service.list(101, 0))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(20, -1))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void detailAndDeleteReturn404ForMissingDocument() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        when(repository.delete(missing)).thenReturn(0);

        assertThatThrownBy(() -> service.detail(missing))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DOC_NOT_FOUND));
        assertThatThrownBy(() -> service.delete(missing))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DOC_NOT_FOUND));
    }

    @Test
    void unsupportedExtensionReturns415BeforeParsing() {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe",
                "application/octet-stream", "data".getBytes());

        assertThatThrownBy(() -> service.upload(file, metadata(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_TYPE));
    }

    @Test
    void emptyFileReturns422() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, metadata(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PARSE_FAILED));
    }

    @Test
    void titleOver200CharsReturns400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        UploadMetadata metadata = new UploadMetadata("x".repeat(201), "doc", "v1",
                LocalDate.of(2025, 1, 1), null, null, null, null, List.of(ChunkStrategy.HEADING));

        assertThatThrownBy(() -> service.upload(file, metadata))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void invertedEffectiveDatesReturn400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        UploadMetadata metadata = new UploadMetadata("t", "doc", "v1",
                LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1), null, null, null,
                List.of(ChunkStrategy.HEADING));

        assertThatThrownBy(() -> service.upload(file, metadata))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void invalidAuthorityLevelReturns400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        UploadMetadata metadata = new UploadMetadata("t", "doc", "v1",
                LocalDate.of(2025, 1, 1), null, "PLANET", null, null, List.of(ChunkStrategy.HEADING));

        assertThatThrownBy(() -> service.upload(file, metadata))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void supersedesRequiresDocKeyVersionAndEffectiveFrom() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(repository.findByChecksum(anyString())).thenReturn(Optional.empty());
        UploadMetadata metadata = new UploadMetadata("t", null, null,
                null, null, null, null, UUID.randomUUID(), List.of(ChunkStrategy.HEADING));

        assertThatThrownBy(() -> service.upload(file, metadata))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void duplicateChecksumShortCircuitsBeforeParsing() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "same content".getBytes());
        DocumentRecord existing = new DocumentRecord(UUID.randomUUID(), "doc", "title", "TXT",
                "upload://a.txt", "checksum", "v1", "bge-m3", LocalDate.of(2025, 1, 1), null,
                "ACTIVE", null, null, null, null, "{}", OffsetDateTime.now(), null);
        when(repository.findByChecksum(anyString())).thenReturn(Optional.of(existing));
        when(repository.chunkCounts(existing.id())).thenReturn(Map.of(ChunkStrategy.HEADING, 2));

        UploadResult result = service.upload(file, metadata(null, null));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.docId()).isEqualTo(existing.id());
        assertThat(result.chunkCounts()).containsEntry(ChunkStrategy.HEADING, 2);
        Mockito.verify(repository, Mockito.never()).insertDocument(Mockito.any());
        Mockito.verify(repository, Mockito.never()).insertChunks(Mockito.anyList());
    }

    private UploadMetadata metadata(String docKey, String version) {
        return new UploadMetadata(null, docKey, version, LocalDate.of(2025, 1, 1), null,
                null, null, null, List.of(ChunkStrategy.HEADING));
    }
}
