package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 多模型命名 Embedding 计算入参
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagMultiModelEmbedRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待向量化的源文本内容
     */
    @NotBlank(message = "待计算文本不能为空")
    private String text;

    /**
     * 指定计算的向量模型标识列表 (可选；若未指定则探测全局所有已注册的 Embedding 模型)
     */
    private List<String> modelNames;
}
