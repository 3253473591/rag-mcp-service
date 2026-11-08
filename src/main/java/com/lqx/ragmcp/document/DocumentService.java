package com.lqx.ragmcp.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.chunk.ChunkingService;
import com.lqx.ragmcp.chunk.PreparedChunk;
import com.lqx.ragmcp.config.UploadProperties;
import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.parse.Block;
import com.lqx.ragmcp.parse.DocumentParserRegistry;
import com.lqx.ragmcp.parse.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Set<String> AUTHORITY_LEVELS =
            Set.of("GROUP", "REGION", "SUBSIDIARY", "DEPARTMENT");
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DOC_KEY_LENGTH = 100;
    private static final int MAX_VERSION_LENGTH = 50;
    private static final int MAX_LIMIT = 100;

    private final DocumentRepository repository;
    private final DocumentParserRegistry parserRegistry;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final UploadProperties uploadProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository repository,
                           DocumentParserRegistry parserRegistry,
                           ChunkingService chunkingService,
                           EmbeddingService embeddingService,
                           UploadProperties uploadProperties,
                           TransactionTemplate transactionTemplate,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.parserRegistry = parserRegistry;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.uploadProperties = uploadProperties;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public UploadResult upload(MultipartFile file, UploadMetadata rawMetadata) {
        long startedAt = System.currentTimeMillis();
        if (file == null || file.isEmpty()) {
            throw ApiException.parseFailed("文件为空");
        }
        String basename = FileNames.basename(file.getOriginalFilename());
        SourceType sourceType = SourceType.fromFilename(basename)
                .orElseThrow(() -> ApiException.unsupportedType(FileNames.extension(basename)));

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.validation("无法读取上传文件");
        }
        if (content.length == 0) {
            throw ApiException.parseFailed("文件为空");
        }
        if (content.length > uploadProperties.getMaxFileSizeBytes()) {
            throw ApiException.fileTooLarge();
        }

        String checksum = sha256(content);
        Optional<DocumentRecord> existing = repository.findByChecksum(checksum);
        if (existing.isPresent()) {
            return duplicateResult(existing.get(), startedAt);
        }

        UploadMetadata metadata = normalize(rawMetadata, basename);
        validateVersionRules(metadata);

        List<Block> blocks = parserRegistry.parserFor(sourceType).parse(content, basename);
        if (blocks.isEmpty()) {
            throw ApiException.parseFailed("文件没有可提取文本");
        }
        Map<ChunkStrategy, List<PreparedChunk>> prepared =
                chunkingService.chunk(blocks, metadata.chunkStrategies());
        List<PreparedChunk> flattened = flatten(prepared, metadata.chunkStrategies());
        List<String> texts = flattened.stream().map(PreparedChunk::content).toList();
        List<float[]> vectors = embeddingService.embed(texts);
        if (vectors.size() != texts.size()) {
            throw ApiException.embeddingUnavailable("embedding 返回数量与输入不一致");
        }

        String documentMetadata = documentMetadataJson(basename, metadata.chunkStrategies());
        Map<ChunkStrategy, Integer> counts = countsOf(prepared, metadata.chunkStrategies());
        try {
            UploadResult result = transactionTemplate.execute(status -> persist(
                    metadata, sourceType, basename, checksum, flattened, vectors, counts,
                    documentMetadata, startedAt));
            if (result == null) {
                throw ApiException.dbUnavailable();
            }
            log.info("document ingested docId={} docKey={} strategies={} chunks={} tookMs={}",
                    result.docId(), result.document().docKey(),
                    result.strategies(), result.totalChunks(), result.tookMs());
            return result;
        } catch (DuplicateKeyException ex) {
            Optional<DocumentRecord> concurrent = repository.findByChecksum(checksum);
            if (concurrent.isPresent()) {
                return duplicateResult(concurrent.get(), startedAt);
            }
            throw ApiException.validation("同一 docKey + version 的文档已存在，不能重复入库");
        }
    }

    public DocumentPage list(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validationField("limit", "limit 必须在 1~100 之间");
        }
        if (offset < 0) {
            throw ApiException.validationField("offset", "offset 必须 >= 0");
        }
        List<DocumentPage.DocumentRow> items = repository.findPage(limit, offset);
        return new DocumentPage(items, repository.countAll(), limit, offset);
    }

    public DocumentDetail detail(UUID docId) {
        DocumentRecord document = repository.findById(docId)
                .orElseThrow(() -> ApiException.docNotFound(docId.toString()));
        return new DocumentDetail(document, repository.chunkCounts(docId));
    }

    public void delete(UUID docId) {
        int deleted = repository.delete(docId);
        if (deleted == 0) {
            throw ApiException.docNotFound(docId.toString());
        }
        log.info("document deleted docId={}", docId);
    }

    private UploadResult persist(UploadMetadata metadata,
                                 SourceType sourceType,
                                 String basename,
                                 String checksum,
                                 List<PreparedChunk> flattened,
                                 List<float[]> vectors,
                                 Map<ChunkStrategy, Integer> counts,
                                 String documentMetadata,
                                 long startedAt) {
        Optional<DocumentRecord> concurrent = repository.findByChecksum(checksum);
        if (concurrent.isPresent()) {
            return duplicateResult(concurrent.get(), startedAt);
        }
        // Re-check inside the transaction so concurrent uploads cannot create overlapping
        // active versions or a second row for the same docKey + version.
        validateVersionRules(metadata);

        UUID docId = UUID.randomUUID();
        if (metadata.supersedesDocId() != null) {
            LocalDate oldEffectiveTo = metadata.effectiveFrom().minusDays(1);
            int updated = repository.markSuperseded(metadata.supersedesDocId(), oldEffectiveTo);
            if (updated == 0) {
                throw ApiException.validationField("supersedesDocId", "supersedesDocId 不存在");
            }
        }

        DocumentRecord record = new DocumentRecord(
                docId,
                metadata.docKey(),
                metadata.title(),
                sourceType.dbValue(),
                "upload://" + basename,
                checksum,
                metadata.version(),
                embeddingService.modelName(),
                metadata.effectiveFrom(),
                metadata.effectiveTo(),
                "ACTIVE",
                metadata.supersedesDocId(),
                metadata.authorityLevel(),
                metadata.scope(),
                null,
                documentMetadata,
                OffsetDateTime.now(),
                null);
        repository.insertDocument(record);
        repository.insertChunks(buildChunkInserts(docId, record, flattened, vectors));
        DocumentRecord stored = repository.findById(docId).orElse(record);
        long tookMs = System.currentTimeMillis() - startedAt;
        return new UploadResult(stored, counts, false, tookMs);
    }

    private List<DocumentRepository.ChunkInsert> buildChunkInserts(UUID docId,
                                                                   DocumentRecord document,
                                                                   List<PreparedChunk> flattened,
                                                                   List<float[]> vectors) {
        List<DocumentRepository.ChunkInsert> inserts = new ArrayList<>(flattened.size());
        for (int i = 0; i < flattened.size(); i++) {
            PreparedChunk chunk = flattened.get(i);
            Map<String, Object> chunkMetadata = new LinkedHashMap<>();
            chunkMetadata.put("docId", docId.toString());
            chunkMetadata.put("title", document.title());
            chunkMetadata.put("version", document.version());
            chunkMetadata.put("chunkIndex", chunk.chunkIndex());
            chunkMetadata.put("chunkStrategy", chunk.strategy().dbValue());
            chunkMetadata.put("table", chunk.table());
            inserts.add(new DocumentRepository.ChunkInsert(
                    UUID.randomUUID(), docId, chunk, toJson(chunkMetadata), vectors.get(i)));
        }
        return inserts;
    }

    private UploadMetadata normalize(UploadMetadata raw, String basename) {
        UploadMetadata source = raw == null
                ? new UploadMetadata(null, null, null, null, null, null, null, null,
                List.of(ChunkStrategy.HEADING))
                : raw;
        String title = source.title();
        if (title == null || title.isBlank()) {
            title = FileNames.titleFromFilename(basename);
        }
        title = title.trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.validationField("title", "title 不能超过 200 字符");
        }
        String docKey = source.docKey();
        if (docKey == null || docKey.isBlank()) {
            docKey = UUID.randomUUID().toString();
        }
        docKey = docKey.trim();
        if (docKey.length() > MAX_DOC_KEY_LENGTH) {
            throw ApiException.validationField("docKey", "docKey 不能超过 100 字符");
        }
        String version = source.version();
        if (version != null) {
            version = version.trim();
            if (version.isEmpty()) {
                version = null;
            }
        }
        if (version != null && version.length() > MAX_VERSION_LENGTH) {
            throw ApiException.validationField("version", "version 不能超过 50 字符");
        }
        LocalDate effectiveFrom = source.effectiveFrom() != null ? source.effectiveFrom() : LocalDate.now();
        LocalDate effectiveTo = source.effectiveTo();
        if (effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.validationField("effectiveFrom", "effectiveFrom 必须早于或等于 effectiveTo");
        }
        String authorityLevel = source.authorityLevel();
        if (authorityLevel != null) {
            authorityLevel = authorityLevel.trim().toUpperCase(Locale.ROOT);
            if (authorityLevel.isEmpty()) {
                authorityLevel = null;
            } else if (!AUTHORITY_LEVELS.contains(authorityLevel)) {
                throw ApiException.validationField("authorityLevel",
                        "authorityLevel 只允许 GROUP/REGION/SUBSIDIARY/DEPARTMENT");
            }
        }
        String scope = source.scope();
        if (scope != null) {
            scope = scope.trim();
            if (scope.isEmpty()) {
                scope = null;
            }
        }
        List<ChunkStrategy> strategies = source.chunkStrategies() == null || source.chunkStrategies().isEmpty()
                ? List.of(ChunkStrategy.HEADING)
                : List.copyOf(source.chunkStrategies());
        if (strategies.size() > 2) {
            throw ApiException.validation("chunkStrategies 最多 2 个");
        }
        if (source.supersedesDocId() != null) {
            if (source.docKey() == null || source.docKey().isBlank()
                    || source.version() == null || source.version().isBlank()
                    || source.effectiveFrom() == null) {
                throw ApiException.validation(
                        "带 supersedesDocId 时 docKey、version、effectiveFrom 必填");
            }
        }
        return new UploadMetadata(title, docKey, version, effectiveFrom, effectiveTo,
                authorityLevel, scope, source.supersedesDocId(), strategies);
    }

    private void validateVersionRules(UploadMetadata metadata) {
        Optional<DocumentRecord> sameVersion =
                repository.findByDocKeyAndVersion(metadata.docKey(), metadata.version());
        if (sameVersion.isPresent()) {
            throw ApiException.validation(
                    "同一 docKey + version 已存在；如需替代旧版请使用新的 version 和 supersedesDocId");
        }
        List<DocumentRepository.ActiveDocument> overlapping = repository.findActiveOverlapping(
                metadata.docKey(), metadata.scope(), metadata.effectiveFrom(), metadata.effectiveTo());
        if (metadata.supersedesDocId() != null) {
            DocumentRecord old = repository.findById(metadata.supersedesDocId())
                    .orElseThrow(() -> ApiException.validationField("supersedesDocId", "supersedesDocId 不存在"));
            if (!old.docKey().equals(metadata.docKey())) {
                throw ApiException.validationField("supersedesDocId", "supersedesDocId 的 docKey 不一致");
            }
            if (!"ACTIVE".equals(old.status())) {
                throw ApiException.validationField("supersedesDocId", "只能替代 ACTIVE 状态的旧版本");
            }
            if (!metadata.effectiveFrom().isAfter(old.effectiveFrom())) {
                throw ApiException.validationField("effectiveFrom",
                        "新版生效日必须晚于旧版生效日");
            }
            UUID oldId = old.id();
            Optional<DocumentRepository.ActiveDocument> otherOverlap = overlapping.stream()
                    .filter(active -> !active.id().equals(oldId))
                    .findFirst();
            if (otherOverlap.isPresent()) {
                throw overlapError(otherOverlap.get());
            }
        } else if (!overlapping.isEmpty()) {
            throw overlapError(overlapping.get(0));
        }
    }

    private ApiException overlapError(DocumentRepository.ActiveDocument active) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conflictDocId", active.id().toString());
        details.put("conflictVersion", active.version());
        details.put("conflictEffectiveFrom", active.effectiveFrom().toString());
        details.put("conflictEffectiveTo",
                active.effectiveTo() == null ? null : active.effectiveTo().toString());
        return ApiException.validation("同一 docKey + scope 存在生效区间重叠的活跃版本", details);
    }

    private UploadResult duplicateResult(DocumentRecord document, long startedAt) {
        Map<ChunkStrategy, Integer> counts = repository.chunkCounts(document.id());
        long tookMs = System.currentTimeMillis() - startedAt;
        log.info("duplicate upload docId={} docKey={} tookMs={}", document.id(), document.docKey(), tookMs);
        return new UploadResult(document, counts, true, tookMs);
    }

    private static List<PreparedChunk> flatten(Map<ChunkStrategy, List<PreparedChunk>> prepared,
                                               List<ChunkStrategy> strategies) {
        List<PreparedChunk> flattened = new ArrayList<>();
        for (ChunkStrategy strategy : strategies) {
            flattened.addAll(prepared.getOrDefault(strategy, List.of()));
        }
        return flattened;
    }

    private static Map<ChunkStrategy, Integer> countsOf(Map<ChunkStrategy, List<PreparedChunk>> prepared,
                                                        List<ChunkStrategy> strategies) {
        Map<ChunkStrategy, Integer> counts = new EnumMap<>(ChunkStrategy.class);
        for (ChunkStrategy strategy : strategies) {
            counts.put(strategy, prepared.getOrDefault(strategy, List.of()).size());
        }
        return counts;
    }

    private String documentMetadataJson(String basename, List<ChunkStrategy> strategies) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filename", basename);
        metadata.put("chunkStrategies", strategies.stream().map(ChunkStrategy::dbValue).toList());
        return toJson(metadata);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("metadata serialization failed", ex);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
