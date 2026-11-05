package io.github.atengk.boot.ai.template;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.rag.ingest.DeduplicationPolicy;
import io.github.atengk.boot.ai.rag.ingest.IngestOptions;
import io.github.atengk.boot.ai.rag.ingest.IngestResult;
import io.github.atengk.boot.ai.rag.parser.DocumentFormat;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.ParseOptions;
import io.github.atengk.boot.ai.rag.parser.ParsedDocument;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import io.github.atengk.boot.ai.rag.splitter.ParentChildSplitter;
import io.github.atengk.boot.ai.rag.splitter.RecursiveCharacterSplitter;
import io.github.atengk.boot.ai.rag.splitter.TableSplitter;
import io.github.atengk.boot.ai.rag.splitter.TokenCharacterSplitter;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.AsyncUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * RAG 端到端 ETL Ingestion 摄取管道与生命周期治理门面 (RagIngestionTemplate)
 * <p>
 * 自动化编排多源异构文档摄取全流程：
 * 格式嗅探 -> 文本/多模态解析 ({@link DocumentParserManager}) -> 规范化治理元数据注入 ->
 * 长文本/Token预算智能切片 ({@link DocumentSplitter}) -> 批处理向量化计算 -> 向量库持久化 ({@link EmbeddingStore})。
 * <p>
 * 内置 {@code OVERWRITE}（覆盖删除重建）与 {@code SKIP}（SHA-256 幂等跳过）生命周期治理策略，
 * 提供对称级联清理接口 {@link #deleteDocument(String)} 与四维上下文透传异步摄取 {@link #ingestAsync}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RagIngestionTemplate {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionTemplate.class);

    private final DocumentParserManager parserManager;
    private final EmbeddingTemplate embeddingTemplate;
    private final DocumentSplitter defaultSplitter;
    private final ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider;
    private final EmbeddingStore<TextSegment> defaultStore;
    private final Executor executor;

    public RagIngestionTemplate(DocumentParserManager parserManager,
                                EmbeddingTemplate embeddingTemplate,
                                DocumentSplitter defaultSplitter) {
        this(parserManager, embeddingTemplate, defaultSplitter, (ObjectProvider<EmbeddingStore<TextSegment>>) null, null);
    }

    public RagIngestionTemplate(DocumentParserManager parserManager,
                                EmbeddingTemplate embeddingTemplate,
                                DocumentSplitter defaultSplitter,
                                EmbeddingStore<TextSegment> defaultStore) {
        this(parserManager, embeddingTemplate, defaultSplitter, defaultStore, null);
    }

    public RagIngestionTemplate(DocumentParserManager parserManager,
                                EmbeddingTemplate embeddingTemplate,
                                DocumentSplitter defaultSplitter,
                                EmbeddingStore<TextSegment> defaultStore,
                                Executor executor) {
        Assert.notNull(parserManager, "parserManager 不能为空");
        Assert.notNull(embeddingTemplate, "embeddingTemplate 不能为空");
        this.parserManager = parserManager;
        this.embeddingTemplate = embeddingTemplate;
        this.defaultSplitter = defaultSplitter != null ? defaultSplitter : new RecursiveCharacterSplitter();
        this.defaultStoreProvider = null;
        this.defaultStore = defaultStore;
        this.executor = executor;
    }

    public RagIngestionTemplate(DocumentParserManager parserManager,
                                EmbeddingTemplate embeddingTemplate,
                                DocumentSplitter defaultSplitter,
                                ObjectProvider<EmbeddingStore<TextSegment>> defaultStoreProvider,
                                Executor executor) {
        Assert.notNull(parserManager, "parserManager 不能为空");
        Assert.notNull(embeddingTemplate, "embeddingTemplate 不能为空");
        this.parserManager = parserManager;
        this.embeddingTemplate = embeddingTemplate;
        this.defaultSplitter = defaultSplitter != null ? defaultSplitter : new RecursiveCharacterSplitter();
        this.defaultStoreProvider = defaultStoreProvider;
        this.defaultStore = null;
        this.executor = executor;
    }

    // ==================== 同步 Ingest API ====================

    /**
     * 摄取 Spring Resource 文档资源
     *
     * @param resource 待摄取文档资源 (不能为空)
     * @param options  摄取治理参数 (可选)
     * @return 摄取执行结果
     */
    public IngestResult ingest(Resource resource, IngestOptions options) {
        Assert.notNull(resource, "resource 不能为空");
        String filename = resource.getFilename();
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = StreamUtils.copyToByteArray(in);
            return doIngestBytes(bytes, filename, options);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取 Resource 资源失败: " + e.getMessage(), e);
        }
    }

    public IngestResult ingest(Resource resource) {
        return ingest(resource, IngestOptions.defaults());
    }

    /**
     * 摄取物理文件 File
     *
     * @param file    待摄取文件 (不能为空且必须存在)
     * @param options 摄取治理参数 (可选)
     * @return 摄取执行结果
     */
    public IngestResult ingest(File file, IngestOptions options) {
        Assert.notNull(file, "file 不能为空");
        Assert.isTrue(file.exists() && file.isFile(), "file 必须存在且为有效文件: " + file.getAbsolutePath());
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return doIngestBytes(bytes, file.getName(), options);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取物理文件失败: " + e.getMessage(), e);
        }
    }

    public IngestResult ingest(File file) {
        return ingest(file, IngestOptions.defaults());
    }

    /**
     * 摄取输入流 InputStream
     *
     * @param inputStream 输入流 (不能为空)
     * @param filename    文档文件名 (用于格式嗅探，如 "manual.pdf")
     * @param options     摄取治理参数 (可选)
     * @return 摄取执行结果
     */
    public IngestResult ingest(InputStream inputStream, String filename, IngestOptions options) {
        Assert.notNull(inputStream, "inputStream 不能为空");
        try (InputStream stream = inputStream) {
            byte[] bytes = StreamUtils.copyToByteArray(stream);
            return doIngestBytes(bytes, filename, options);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取输入流数据失败: " + e.getMessage(), e);
        }
    }

    public IngestResult ingest(InputStream inputStream, String filename) {
        return ingest(inputStream, filename, IngestOptions.defaults());
    }

    /**
     * 摄取纯文本或 Markdown 字符串内容
     *
     * @param text     长文本内容 (不能为空)
     * @param docName  文档名称标识
     * @param options  摄取治理参数 (可选)
     * @return 摄取执行结果
     */
    public IngestResult ingest(String text, String docName, IngestOptions options) {
        Assert.hasText(text, "摄取文本内容 text 不能为空");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return doIngestBytes(bytes, StringUtils.hasText(docName) ? docName : "document.txt", options);
    }

    public IngestResult ingest(String text, String docName) {
        return ingest(text, docName, IngestOptions.defaults());
    }

    /**
     * 摄取纯文本或 Markdown 字符串内容 (使用 options 中的 docName 或保底 document.txt)
     *
     * @param text    长文本内容 (不能为空)
     * @param options 摄取治理参数 (可选)
     * @return 摄取执行结果
     */
    public IngestResult ingest(String text, IngestOptions options) {
        String docName = options != null && StringUtils.hasText(options.getDocName()) ? options.getDocName().trim() : "document.txt";
        return ingest(text, docName, options);
    }

    public IngestResult ingest(String text) {
        return ingest(text, "document.txt", IngestOptions.defaults());
    }

    // ==================== 批量 Ingest API ====================

    /**
     * 同步批量摄取 Resource 文档列表
     *
     * @param resources 待摄取文档资源列表 (不能为空)
     * @param options   摄取治理参数 (可选)
     * @return 批量摄取结果列表
     */
    public List<IngestResult> ingestBatch(List<? extends Resource> resources, IngestOptions options) {
        Assert.notNull(resources, "resources 不能为空");
        return resources.stream().map(res -> ingest(res, options)).toList();
    }

    public List<IngestResult> ingestBatch(List<? extends Resource> resources) {
        return ingestBatch(resources, IngestOptions.defaults());
    }

    /**
     * 异步并发批量摄取 Resource 文档，支持完成与异常事件回调
     *
     * @param resources 待摄取文档资源列表 (不能为空)
     * @param options   摄取治理参数 (可选)
     * @param onSuccess 单个资源摄取成功时的回调函数 (可选)
     * @param onError   单个资源摄取失败时的回调函数 (可选)
     * @return 批量执行 CompletableFuture 结果列表
     */
    public CompletableFuture<List<IngestResult>> ingestBatchAsync(
            List<? extends Resource> resources,
            IngestOptions options,
            Consumer<IngestResult> onSuccess,
            Consumer<Throwable> onError) {
        Assert.notNull(resources, "resources 不能为空");
        if (resources.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<CompletableFuture<IngestResult>> futures = resources.stream()
                .map(res -> ingestAsync(res, options)
                        .whenComplete((resResult, ex) -> {
                            if (ex != null) {
                                if (onError != null) {
                                    onError.accept(ex);
                                }
                            } else {
                                if (onSuccess != null) {
                                    onSuccess.accept(resResult);
                                }
                            }
                        }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<List<IngestResult>> ingestBatchAsync(
            List<? extends Resource> resources,
            IngestOptions options) {
        return ingestBatchAsync(resources, options, null, null);
    }

    public CompletableFuture<List<IngestResult>> ingestBatchAsync(List<? extends Resource> resources) {
        return ingestBatchAsync(resources, IngestOptions.defaults(), null, null);
    }

    // ==================== 异步 Ingest API ====================

    /**
     * 异步多线程摄取 Resource 文档，挂载四维上下文快照
     *
     * @param resource 待摄取文档资源
     * @param options  摄取选项
     * @return 异步 CompletableFuture 结果
     */
    public CompletableFuture<IngestResult> ingestAsync(Resource resource, IngestOptions options) {
        Assert.notNull(resource, "resource 不能为空");
        return supplyAsync(() -> ingest(resource, options));
    }

    public CompletableFuture<IngestResult> ingestAsync(Resource resource) {
        return ingestAsync(resource, IngestOptions.defaults());
    }

    public CompletableFuture<IngestResult> ingestAsync(File file, IngestOptions options) {
        Assert.notNull(file, "file 不能为空");
        return supplyAsync(() -> ingest(file, options));
    }

    public CompletableFuture<IngestResult> ingestAsync(File file) {
        return ingestAsync(file, IngestOptions.defaults());
    }

    public CompletableFuture<IngestResult> ingestAsync(InputStream inputStream, String filename, IngestOptions options) {
        Assert.notNull(inputStream, "inputStream 不能为空");
        return supplyAsync(() -> ingest(inputStream, filename, options));
    }

    public CompletableFuture<IngestResult> ingestAsync(InputStream inputStream, String filename) {
        return ingestAsync(inputStream, filename, IngestOptions.defaults());
    }

    public CompletableFuture<IngestResult> ingestAsync(String text, String docName, IngestOptions options) {
        Assert.hasText(text, "text 不能为空");
        return supplyAsync(() -> ingest(text, docName, options));
    }

    public CompletableFuture<IngestResult> ingestAsync(String text, String docName) {
        return ingestAsync(text, docName, IngestOptions.defaults());
    }

    /**
     * 异步摄取纯文本或 Markdown 字符串内容 (使用 options 中的 docName 或保底 document.txt)
     *
     * @param text    长文本内容 (不能为空)
     * @param options 摄取治理参数 (可选)
     * @return 异步 CompletableFuture 结果
     */
    public CompletableFuture<IngestResult> ingestAsync(String text, IngestOptions options) {
        String docName = options != null && StringUtils.hasText(options.getDocName()) ? options.getDocName().trim() : "document.txt";
        return ingestAsync(text, docName, options);
    }

    public CompletableFuture<IngestResult> ingestAsync(String text) {
        return ingestAsync(text, "document.txt", IngestOptions.defaults());
    }

    // ==================== 级联清理 API ====================

    /**
     * 从指定向量库依据 doc_id 物理级联清空文档所属所有向量切片 (对称运维)
     *
     * @param store 目标向量存储库
     * @param docId 文档唯一业务标识 (不能为空)
     */
    public void deleteDocument(EmbeddingStore<TextSegment> store, String docId) {
        Assert.hasText(docId, "docId 不能为空");
        if (store == null) {
            throw new IllegalArgumentException("目标 EmbeddingStore 不能为空");
        }
        Filter filter = MetadataFilterBuilder.metadataKey("doc_id").isEqualTo(docId.trim());
        try {
            store.removeAll(filter);
            log.info("RAG Ingestion: 成功依据 doc_id 级联清理知识库文档切片 [docId={}]", docId.trim());
        } catch (UnsupportedOperationException e) {
            log.warn("目标向量库 [{}] 不支持 removeAll(Filter) 级联物理删除，尝试适配降级清理: {}",
                    store.getClass().getSimpleName(), e.getMessage());
            cleanStoreIfUnsupported(store, docId.trim());
        }
    }

    private void cleanStoreIfUnsupported(EmbeddingStore<TextSegment> store, String docId) {
        if (store == null) {
            return;
        }
        // 尝试针对 RedisEmbeddingStore 反射获取 Jedis 物理清理
        if (store.getClass().getName().contains("RedisEmbeddingStore")) {
            try {
                java.lang.reflect.Field clientField = store.getClass().getDeclaredField("client");
                clientField.setAccessible(true);
                Object jedis = clientField.get(store);

                java.lang.reflect.Field schemaField = store.getClass().getDeclaredField("schema");
                schemaField.setAccessible(true);
                Object schema = schemaField.get(store);

                if (jedis != null && schema != null) {
                    java.lang.reflect.Field prefixField = schema.getClass().getDeclaredField("prefix");
                    prefixField.setAccessible(true);
                    String prefix = (String) prefixField.get(schema);

                    java.lang.reflect.Method keysMethod = jedis.getClass().getMethod("keys", String.class);
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> keys = (java.util.Set<String>) keysMethod.invoke(jedis, (prefix != null ? prefix : "") + "*");

                    if (keys != null && !keys.isEmpty()) {
                        int deletedCount = 0;
                        java.lang.reflect.Method delMethod = jedis.getClass().getMethod("del", String.class);
                        java.lang.reflect.Method jsonGetMethod = null;
                        java.lang.reflect.Method hgetAllMethod = null;
                        try {
                            jsonGetMethod = jedis.getClass().getMethod("jsonGet", String.class);
                        } catch (NoSuchMethodException ignored) {
                        }
                        try {
                            hgetAllMethod = jedis.getClass().getMethod("hgetAll", String.class);
                        } catch (NoSuchMethodException ignored) {
                        }

                        for (String key : keys) {
                            boolean match = false;
                            // 优先检查 RedisJSON
                            if (jsonGetMethod != null) {
                                try {
                                    Object jsonObj = jsonGetMethod.invoke(jedis, key);
                                    if (jsonObj != null && String.valueOf(jsonObj).contains(docId)) {
                                        match = true;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            // 其次检查 Hash
                            if (!match && hgetAllMethod != null) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    java.util.Map<String, String> data = (java.util.Map<String, String>) hgetAllMethod.invoke(jedis, key);
                                    if (data != null && docId.equals(data.get("doc_id"))) {
                                        match = true;
                                    }
                                } catch (Exception ignored) {
                                }
                            }

                            if (match) {
                                delMethod.invoke(jedis, key);
                                deletedCount++;
                            }
                        }
                        log.info("RAG Ingestion: Redis 降级清理完成，成功物理清理匹配 [doc_id={}] 的切片 Key 数量: {}", docId, deletedCount);
                    }
                }
            } catch (Exception ex) {
                log.warn("Redis 降级清理切片发生异常 [docId={}]: {}", docId, ex.getMessage());
            }
        }
    }

    /**
     * 从默认向量库依据 doc_id 物理级联清空文档所属所有向量切片
     *
     * @param docId 文档唯一业务标识 (不能为空)
     */
    public void deleteDocument(String docId) {
        deleteDocument(resolveStore(null), docId);
    }

    // ==================== 核心执行流水线 ====================

    private IngestResult doIngestBytes(byte[] bytes, String defaultDocName, IngestOptions options) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("待摄取文档字节内容不能为空");
        }

        long startNano = System.nanoTime();
        IngestOptions opts = options != null ? options : IngestOptions.defaults();
        String docHash = computeSha256(bytes);

        String docId = StringUtils.hasText(opts.getDocId()) ? opts.getDocId().trim() : UUID.randomUUID().toString();
        String docName = StringUtils.hasText(opts.getDocName())
                ? opts.getDocName().trim()
                : (StringUtils.hasText(defaultDocName) ? defaultDocName.trim() : "document.txt");
        String tenantId = StringUtils.hasText(opts.getTenantId()) ? opts.getTenantId().trim() : TenantContextHolder.getTenantId();
        DeduplicationPolicy policy = opts.getPolicy() != null ? opts.getPolicy() : DeduplicationPolicy.OVERWRITE;
        EmbeddingStore<TextSegment> store = resolveStore(opts);

        // 1. 幂等去重生命周期治理
        if (policy == DeduplicationPolicy.SKIP) {
            boolean alreadyExists = checkDocumentHashExists(store, docHash);
            if (alreadyExists) {
                Duration duration = Duration.ofNanos(System.nanoTime() - startNano);
                log.info("RAG Ingestion: 文档内容 SHA-256 哈希匹配，触发 SKIP 幂等策略跳过解析与切片 [docId={}, hash={}]", docId, docHash);
                return IngestResult.skipped(docId, docName, docHash, tenantId, duration);
            }
        } else if (policy == DeduplicationPolicy.OVERWRITE) {
            // 覆盖写入：写入前执行级联清理
            deleteDocument(store, docId);
        }

        // 2. 格式嗅探与异构文档解析
        ParseOptions parseOptions = opts.getParseOptions() != null
                ? opts.getParseOptions()
                : ParseOptions.ofFilename(docName);
        ParsedDocument parsedDoc = this.parserManager.parse(new ByteArrayInputStream(bytes), parseOptions);
        String text = parsedDoc != null ? parsedDoc.getText() : null;

        if (!StringUtils.hasText(text)) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNano);
            log.warn("RAG Ingestion: 文档解析后纯文本内容为空 [docId={}, docName={}]", docId, docName);
            return IngestResult.success(docId, docName, docHash, tenantId, 0, Collections.emptyList(), duration, Map.of());
        }

        // 3. 规范化治理元数据注入
        Metadata baseMetadata = new Metadata();
        baseMetadata.put("doc_id", docId);
        baseMetadata.put("doc_name", docName);
        baseMetadata.put("doc_hash", docHash);
        if (StringUtils.hasText(tenantId)) {
            baseMetadata.put("tenant_id", tenantId);
        }
        baseMetadata.put("created_at", Instant.now().toString());

        String userId = UserContextHolder.getUserId();
        if (StringUtils.hasText(userId)) {
            baseMetadata.put("user_id", userId);
        }

        // 合并用户自定义业务元数据
        if (opts.getCustomMetadata() != null && !opts.getCustomMetadata().isEmpty()) {
            opts.getCustomMetadata().forEach((k, v) -> {
                if (v != null) {
                    putSafeMetadata(baseMetadata, k, v);
                }
            });
        }

        // 合并解析器产出的文档属性
        if (parsedDoc != null && parsedDoc.getMetadata() != null) {
            parsedDoc.getMetadata().forEach((k, v) -> {
                if (v != null && !baseMetadata.toMap().containsKey(k)) {
                    putSafeMetadata(baseMetadata, k, v);
                }
            });
        }

        // 4. 智能分块切片
        DocumentSplitter splitter = resolveSplitter(opts);
        List<TextSegment> rawSegments = splitter.split(text, baseMetadata);

        if (rawSegments == null || rawSegments.isEmpty()) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNano);
            return IngestResult.success(docId, docName, docHash, tenantId, 0, Collections.emptyList(), duration, Map.of());
        }

        // 保底注入规范化分块元数据契约 (chunk_index, parent_id)
        List<TextSegment> segments = new ArrayList<>(rawSegments.size());
        for (int i = 0; i < rawSegments.size(); i++) {
            TextSegment seg = rawSegments.get(i);
            Metadata segMeta = seg.metadata() != null ? seg.metadata().copy() : new Metadata();
            if (!segMeta.toMap().containsKey("chunk_index")) {
                segMeta.put("chunk_index", i);
            }
            if (!segMeta.toMap().containsKey("parent_id")) {
                segMeta.put("parent_id", "");
            }
            segments.add(TextSegment.from(seg.text(), segMeta));
        }

        // 5. 批处理向量化计算并持久化至向量库
        List<String> segmentIds;
        if (StringUtils.hasText(opts.getEmbeddingModelName())) {
            segmentIds = this.embeddingTemplate.ingestAllWithModel(opts.getEmbeddingModelName().trim(), store, segments);
        } else {
            segmentIds = this.embeddingTemplate.ingestAll(store, segments);
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - startNano);
        log.info("RAG Ingestion: 成功完成文档摄取入库 [docId={}, docName={}, 切片数={}, 耗时={}ms]",
                docId, docName, segments.size(), duration.toMillis());

        return IngestResult.success(docId, docName, docHash, tenantId, segments.size(), segmentIds, duration, Map.of());
    }

    private boolean checkDocumentHashExists(EmbeddingStore<TextSegment> store, String docHash) {
        if (store == null || !StringUtils.hasText(docHash)) {
            return false;
        }
        try {
            int dim = 1536;
            try {
                dim = this.embeddingTemplate.dimension();
            } catch (Exception ignored) {
                // 无法探测维度时保底回退 1536
            }
            Embedding probe;
            try {
                probe = this.embeddingTemplate.embed("probe").content();
            } catch (Exception e) {
                probe = new Embedding(new float[dim]);
            }

            Filter filter = MetadataFilterBuilder.metadataKey("doc_hash").isEqualTo(docHash.trim());
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(probe)
                    .filter(filter)
                    .maxResults(1)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = store.search(request);
            if (searchResult != null && searchResult.matches() != null && !searchResult.matches().isEmpty()) {
                TextSegment matched = searchResult.matches().get(0).embedded();
                if (matched != null && matched.metadata() != null) {
                    String existingHash = matched.metadata().getString("doc_hash");
                    return docHash.trim().equals(existingHash);
                }
            }
        } catch (Exception e) {
            log.warn("RAG Ingestion: 探测知识库文档现有哈希存在性异常，平滑降级执行摄取 [docHash={}, error={}]", docHash, e.getMessage());
        }
        return false;
    }

    /**
     * 根据 IngestOptions 动态解析切片器实例
     *
     * @param options 摄取治理选项
     * @return 生效的 DocumentSplitter 实例
     */
    public DocumentSplitter resolveSplitter(IngestOptions options) {
        if (options != null && options.getSplitter() != null) {
            return options.getSplitter();
        }
        if (options != null && (options.getSplitterType() != null || options.getChunkSize() != null)) {
            AtengAiRagProperties.SplitterType type = options.getSplitterType() != null
                    ? options.getSplitterType()
                    : AtengAiRagProperties.SplitterType.RECURSIVE;
            return switch (type) {
                case RECURSIVE -> {
                    int size = options.getChunkSize() != null ? options.getChunkSize() : RecursiveCharacterSplitter.DEFAULT_CHUNK_SIZE;
                    int overlap = options.getChunkOverlap() != null ? options.getChunkOverlap() : Math.min(RecursiveCharacterSplitter.DEFAULT_CHUNK_OVERLAP, Math.max(0, size / 10));
                    yield new RecursiveCharacterSplitter(size, overlap);
                }
                case TOKEN -> {
                    int size = options.getChunkSize() != null ? options.getChunkSize() : TokenCharacterSplitter.DEFAULT_MAX_TOKENS;
                    int overlap = options.getChunkOverlap() != null ? options.getChunkOverlap() : Math.min(TokenCharacterSplitter.DEFAULT_TOKEN_OVERLAP, Math.max(0, size / 10));
                    yield new TokenCharacterSplitter(size, overlap);
                }
                case PARENT_CHILD -> {
                    int childSize = options.getChunkSize() != null ? options.getChunkSize() : ParentChildSplitter.DEFAULT_CHILD_CHUNK_SIZE;
                    int childOverlap = options.getChunkOverlap() != null ? options.getChunkOverlap() : ParentChildSplitter.DEFAULT_CHILD_CHUNK_OVERLAP;
                    int parentSize = Math.max(childSize * 4, ParentChildSplitter.DEFAULT_PARENT_CHUNK_SIZE);
                    int parentOverlap = Math.max(childOverlap * 2, ParentChildSplitter.DEFAULT_PARENT_CHUNK_OVERLAP);
                    yield new ParentChildSplitter(parentSize, parentOverlap, childSize, childOverlap);
                }
                case TABLE -> {
                    int rows = options.getChunkSize() != null ? options.getChunkSize() : TableSplitter.DEFAULT_ROWS_PER_CHUNK;
                    int overlap = options.getChunkOverlap() != null ? options.getChunkOverlap() : TableSplitter.DEFAULT_ROW_OVERLAP;
                    yield new TableSplitter(rows, overlap);
                }
            };
        }
        return this.defaultSplitter;
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        if (this.executor != null) {
            Supplier<T> wrapped = AsyncContext.wrapSupplier(supplier);
            return CompletableFuture.supplyAsync(wrapped, this.executor);
        }
        return AsyncUtil.supply(supplier);
    }

    public EmbeddingStore<TextSegment> resolveStore(IngestOptions options) {
        if (options != null && options.getEmbeddingStore() != null) {
            return options.getEmbeddingStore();
        }
        if (this.defaultStoreProvider != null) {
            EmbeddingStore<TextSegment> store = this.defaultStoreProvider.getIfAvailable();
            if (store != null) {
                return store;
            }
        }
        if (this.defaultStore != null) {
            return this.defaultStore;
        }
        return this.embeddingTemplate.resolveDefaultStore();
    }

    private static void putSafeMetadata(Metadata meta, String key, Object val) {
        if (val instanceof Integer i) {
            meta.put(key, i);
        } else if (val instanceof Long l) {
            meta.put(key, l);
        } else if (val instanceof Float f) {
            meta.put(key, f);
        } else if (val instanceof Double d) {
            meta.put(key, d);
        } else if (val instanceof UUID u) {
            meta.put(key, u);
        } else {
            meta.put(key, String.valueOf(val));
        }
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法在当前运行环境中不可用", e);
        }
    }

    public DocumentParserManager getParserManager() {
        return parserManager;
    }

    public EmbeddingTemplate getEmbeddingTemplate() {
        return embeddingTemplate;
    }

    public DocumentSplitter getDefaultSplitter() {
        return defaultSplitter;
    }

    public Executor getExecutor() {
        return executor;
    }
}
