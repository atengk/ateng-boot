package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 动态提示词版本注册请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptRegisterRequest {

    /**
     * 提示词编码
     */
    @NotBlank(message = "提示词编码 code 不能为空")
    private String code;

    /**
     * 版本号
     */
    private String version;

    /**
     * 发布标签（如 prod / canary）
     */
    private String label;

    /**
     * 提示词正文模板
     */
    @NotBlank(message = "提示词正文 content 不能为空")
    private String content;

    /**
     * 必填占位变量声明
     */
    private List<String> requiredVariables;

    /**
     * 提示词用途描述
     */
    private String description;
}
