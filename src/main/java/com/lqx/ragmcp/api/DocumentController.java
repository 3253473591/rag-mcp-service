package com.lqx.ragmcp.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.dto.DocumentDetailResponse;
import com.lqx.ragmcp.api.dto.DocumentListResponse;
import com.lqx.ragmcp.api.dto.UploadResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.chunk.ChunkStrategies;
import com.lqx.ragmcp.document.DocumentService;
import com.lqx.ragmcp.document.UploadMetadata;
import com.lqx.ragmcp.document.UploadResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    public DocumentController(DocumentService documentService, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "docKey", required = false) String docKey,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "effectiveFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @RequestParam(name = "effectiveTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo,
            @RequestParam(name = "authorityLevel", required = false) String authorityLevel,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "supersedesDocId", required = false) String supersedesDocId,
            @RequestParam(name = "chunkStrategies", required = false) List<String> chunkStrategies) {

        UploadMetadata metadata = new UploadMetadata(
                title,
                docKey,
                version,
                effectiveFrom,
                effectiveTo,
                authorityLevel,
                scope,
                parseUuid(supersedesDocId, "supersedesDocId"),
                ChunkStrategies.parse(chunkStrategies));

        UploadResult result = documentService.upload(file, metadata);
        UploadResponse body = DocumentApiMapper.toUploadResponse(result);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public DocumentListResponse list(@RequestParam(name = "limit", defaultValue = "20") int limit,
                                     @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return DocumentApiMapper.toListResponse(documentService.list(limit, offset));
    }

    @GetMapping("/{docId}")
    public DocumentDetailResponse detail(@PathVariable("docId") String docId) {
        return DocumentApiMapper.toDetailResponse(
                documentService.detail(parseUuid(docId, "docId")), objectMapper);
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(@PathVariable("docId") String docId) {
        documentService.delete(parseUuid(docId, "docId"));
        return ResponseEntity.noContent().build();
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.validationField(field, field + " 必须是合法 UUID");
        }
    }
}
