package io.github.atengk.boot.ai.rag.ingest;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档 Ingestion 摄取执行结果
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class IngestResult {

    /**
     * 文档业务唯一标识
     */
    String docId;

    /**
     * 原始文档名称
     */
    String docName;

    /**
     * 原始文档内容 SHA-256 摘要哈希
     */
    String docHash;

    /**
     * 归属租户标识
     */
    String tenantId;

    /**
     * 本次成功切分并持久化入库的切片片段总数
     */
    int chunkCount;

    /**
     * 是否触发幂等策略被跳过 (SKIP 策略下命中完全相同的内容哈希时为 true)
     */
    boolean skipped;

    /**
     * 本次 ETL 管道端到端耗时
     */
    Duration executionDuration;

    /**
     * 写入向量数据库后由存储引擎返回的向量记录唯一 ID 列表
     */
    List<String> segmentIds;

    /**
     * 摄取附加诊断或来源元数据
     */
    Map<String, Object> metadata;

    public boolean isSuccess() {
        return skipped || chunkCount > 0;
    }

    public List<String> getSegmentIds() {
        return segmentIds != null ? Collections.unmodifiableList(segmentIds) : Collections.emptyList();
    }

    public Map<String, Object> getMetadata() {
        return metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public static IngestResult skipped(String docId, String docName, String docHash, String tenantId, Duration duration) {
        return IngestResult.builder()
                .docId(docId)
                .docName(docName)
                .docHash(docHash)
                .tenantId(tenantId)
                .chunkCount(0)
                .skipped(true)
                .executionDuration(duration)
                .segmentIds(Collections.emptyList())
                .metadata(Collections.emptyMap())
                .build();
    }

    public static IngestResult success(String docId, String docName, String docHash, String tenantId,
                                       int chunkCount, List<String> segmentIds, Duration duration,
                                       Map<String, Object> metadata) {
        return IngestResult.builder()
                .docId(docId)
                .docName(docName)
                .docHash(docHash)
                .tenantId(tenantId)
                .chunkCount(chunkCount)
                .skipped(false)
                .executionDuration(duration)
                .segmentIds(segmentIds != null ? List.copyOf(segmentIds) : Collections.emptyList())
                .metadata(metadata != null ? Map.copyOf(metadata) : Collections.emptyMap())
                .build();
    }
}
