package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 检索候选两阶段重排请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagRerankRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询关键词或意图
     */
    @NotBlank(message = "查询关键词不能为空")
    private String query;

    /**
     * 待重排的候选文本切片列表
     */
    @NotEmpty(message = "候选切片列表不能为空")
    private List<String> candidates;

    /**
     * 返回 Top-K 结果数量 (可选，默认 3)
     */
    @Builder.Default
    private Integer topK = 3;
}
