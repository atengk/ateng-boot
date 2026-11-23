package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 流式保底 Token 估算测试入参 DTO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AiStreamFallbackRequestDto {

    /**
     * 用户提示词
     */
    @NotBlank(message = "用户提示词不能为空")
    private String prompt = "你好世界，这是一段中文测试。";

    /**
     * 模拟模型流式吐出正文
     */
    @NotNull(message = "流式响应正文不能为 null")
    private String responseText = "Hello world! This is a test response.";
}
