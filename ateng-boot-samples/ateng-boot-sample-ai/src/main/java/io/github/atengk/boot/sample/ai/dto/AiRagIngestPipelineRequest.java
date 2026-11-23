package io.github.atengk.boot.sample.ai.dto;

import io.github.atengk.boot.ai.rag.ingest.DeduplicationPolicy;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 端到端 ETL Ingestion 管道摄取请求入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagIngestPipelineRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档业务唯一标识
     */
    @NotBlank(message = "文档唯一标识 docId 不能为空")
    private String docId;

    /**
     * 原始文档业务名称
     */
    @NotBlank(message = "文档名称 docName 不能为空")
    private String docName;

    /**
     * 文档内容文本
     */
    @NotBlank(message = "文档文本内容不能为空")
    private String content;

    /**
     * 幂等去重生命周期策略 (OVERWRITE, SKIP, APPEND，默认 OVERWRITE)
     */
    @Builder.Default
    private DeduplicationPolicy policy = DeduplicationPolicy.OVERWRITE;

    /**
     * 归属租户唯一标识 (可选；未指定时自动从当前租户上下文继承)
     */
    private String tenantId;

    /**
     * 附加自定义业务元数据
     */
    private Map<String, Object> customMetadata;
}
