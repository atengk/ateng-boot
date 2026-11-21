package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 动态提示词渲染与模型对话请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptRenderRequest {

    /**
     * 提示词编码
     */
    @NotBlank(message = "提示词编码 code 不能为空")
    private String code;

    /**
     * 可选版本号或发布标签（如 prod / canary / 1.0.0）
     */
    private String versionOrLabel;

    /**
     * 可选模型标识名称
     */
    private String model;

    /**
     * 占位符变量映射
     */
    private Map<String, Object> variables;

    /**
     * 是否启用严格变量校验（默认 true）
     */
    private boolean strict = true;
}
