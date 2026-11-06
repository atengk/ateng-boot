package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 挂载 OpenAPI 受管工具的大模型对话请求参数
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class OpenApiChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户提示词
     */
    @NotBlank(message = "用户提示词 prompt 不能为空")
    private String prompt;

    /**
     * 契约类路径资源位置（默认 openapi/order-service-api.json）
     */
    private String specResource = "openapi/order-service-api.json";
}
