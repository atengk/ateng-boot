package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * OpenAPI 工具直接调用请求参数
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class OpenApiExecuteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标操作唯一标识 operationId
     */
    @NotBlank(message = "操作标识 operationId 不能为空")
    private String operationId;

    /**
     * 工具调用入参字典（支持路径参数、查询参数与请求体）
     */
    private Map<String, Object> arguments;
}
