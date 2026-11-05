package io.github.atengk.boot.ai.rag.ingest;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.rag.parser.ParseOptions;
import io.github.atengk.boot.ai.rag.splitter.DocumentSplitter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 文档 Ingestion 摄取治理参数选项
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestOptions {

    /**
     * 文档全局唯一业务标识 (如 DOC-001；若未指定则自动基于文件哈希或 UUID 派生)
     */
    private String docId;

    /**
     * 原始文档业务名称 (如 "系统架构指引.docx")
     */
    private String docName;

    /**
     * 归属租户唯一标识 (未配置时自动从 TenantContextHolder 提取)
     */
    private String tenantId;

    /**
     * 幂等去重生命周期治理策略 (默认 OVERWRITE 覆盖更新)
     */
    @Builder.Default
    private DeduplicationPolicy policy = DeduplicationPolicy.OVERWRITE;

    /**
     * 自定义长文档智能分块切片器 (为 null 时自动使用全局默认 DocumentSplitter 门面)
     */
    private DocumentSplitter splitter;

    /**
     * 单个文本分块的目标 Token / 字符大小 (可选；未配置时使用全局切片器默认配置)
     */
    private Integer chunkSize;

    /**
     * 分块重叠重合 Token / 字符大小 (可选；未配置时使用全局切片器默认配置)
     */
    private Integer chunkOverlap;

    /**
     * 智能分块切片算法类型 (可选；为 null 时自动使用全局默认 SplitterType)
     */
    private AtengAiRagProperties.SplitterType splitterType;

    /**
     * 文档底层解析专属配置选项 (可选)
     */
    private ParseOptions parseOptions;

    /**
     * 动态指定 Embedding 模型别名 (可选，依托 EmbeddingModelRegistry 动态切源)
     */
    private String embeddingModelName;

    /**
     * 显式指定向量存储库实例 (可选；为 null 时自动绑定全局默认 EmbeddingStore)
     */
    private EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 挂载至每一个切片 Metadata 的自定义扩展业务元数据字典
     */
    @Builder.Default
    private Map<String, Object> customMetadata = new LinkedHashMap<>();

    public static IngestOptions defaults() {
        return IngestOptions.builder().build();
    }

    public static IngestOptions ofDocId(String docId) {
        return IngestOptions.builder().docId(docId).build();
    }

    public static IngestOptions ofDocId(String docId, String docName) {
        return IngestOptions.builder().docId(docId).docName(docName).build();
    }
}
