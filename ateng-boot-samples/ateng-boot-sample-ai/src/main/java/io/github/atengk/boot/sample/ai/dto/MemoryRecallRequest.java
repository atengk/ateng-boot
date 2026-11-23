package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 长效情景语义记忆检索召回请求传输契约
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecallRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 语义检索查询词或当前用户问题
     */
    @NotBlank(message = "查询文本 query 不能为空")
    private String query;

    /**
     * 目标用户标识 (可选，缺省从上下文读取)
     */
    private String userId;

    /**
     * 最大召回数量 Top-K
     */
    @Min(value = 1, message = "最大召回数不能小于 1")
    @Max(value = 50, message = "最大召回数不能超过 50")
    private Integer maxResults;

    /**
     * 最低相似度阈值 (0.0 ~ 1.0)
     */
    @DecimalMin(value = "0.0", message = "最低相似度阈值不能小于 0.0")
    @DecimalMax(value = "1.0", message = "最低相似度阈值不能大于 1.0")
    private Double minScore;
}
