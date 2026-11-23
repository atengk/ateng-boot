package io.github.atengk.boot.ai.mcp;

import java.util.Collections;
import java.util.List;

/**
 * MCP 服务端暴露的标准只读提示词模板描述模型。
 *
 * @param name        提示词模板唯一名称
 * @param description 提示词用途说明
 * @param arguments   模板入参参数元数据列表
 * @author Ateng
 * @since 2026-11-21
 */
public record McpPrompt(
        String name,
        String description,
        List<McpPromptArgument> arguments
) {
    public McpPrompt {
        arguments = arguments != null ? Collections.unmodifiableList(arguments) : Collections.emptyList();
    }

    /**
     * MCP 提示词模板单个参数规格。
     *
     * @param name        参数名
     * @param description 参数描述
     * @param required    是否必填
     */
    public record McpPromptArgument(
            String name,
            String description,
            boolean required
    ) {
    }
}
