package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文本智能切片请求传输对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagSplitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待切分原始文档文本
     */
    @NotBlank(message = "待切分文本不能为空")
    private String text;

    /**
     * 切片策略：RECURSIVE (递归字符，默认) 或 MARKDOWN (按层级标题)
     */
    @Builder.Default
    private String strategy = "RECURSIVE";

    /**
     * 单切片目标字符大小 (可选，默认 500)
     */
    private Integer chunkSize;

    /**
     * 切片重叠字符大小 (可选，默认 50)
     */
    private Integer chunkOverlap;
}
