package io.github.atengk.boot.ai.mcp;

/**
 * MCP 服务端暴露的标准只读资源描述模型。
 *
 * @param uri         资源唯一 URI（如 {@code file:///README.md}、{@code postgres://schema/users}）
 * @param name        资源显示名称
 * @param description 资源用途说明
 * @param mimeType    资源媒体类型（如 {@code text/plain}、{@code application/json}）
 * @author Ateng
 * @since 2026-11-21
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType
) {
}
