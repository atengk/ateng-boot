package io.github.atengk.boot.sample.ai.dto;

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
    private String prompt = "你好世界，这是一段中文测试。";

    /**
     * 模拟模型流式吐出正文
     */
    private String responseText = "Hello world! This is a test response.";
}
