package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态元数据与多租户权限过滤检索请求入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagFilteredRetrieveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 检索问题文本
     */
    @NotBlank(message = "检索问题不能为空")
    private String query;

    /**
     * 模拟请求携带的租户标识 (可选，用于模拟租户上下文注入)
     */
    private String tenantId;

    /**
     * 业务分类过滤条件 (可选，如 tech, finance, rule 等)
     */
    private String category;

    /**
     * 最大召回数量 Top-K (默认 3)
     */
    @Builder.Default
    private Integer maxResults = 3;

    /**
     * 最低相似度阈值 (可选，0.0 ~ 1.0)
     */
    private Double minScore;
}
