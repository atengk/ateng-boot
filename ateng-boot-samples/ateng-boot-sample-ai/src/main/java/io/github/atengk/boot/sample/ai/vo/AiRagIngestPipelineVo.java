package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 端到端 ETL Ingestion 摄取执行响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagIngestPipelineVo {

    /**
     * 文档业务唯一标识
     */
    String docId;

    /**
     * 文档原始名称
     */
    String docName;

    /**
     * 文档内容 SHA-256 摘要哈希
     */
    String docHash;

    /**
     * 归属租户
     */
    String tenantId;

    /**
     * 本次成功切分并持久化入库的切片片段数
     */
    int chunkCount;

    /**
     * 是否命中幂等跳过策略
     */
    boolean skipped;

    /**
     * 摄取流水线总耗时 (毫秒)
     */
    long executionDurationMs;

    /**
     * 结果状态描述
     */
    String message;
}
