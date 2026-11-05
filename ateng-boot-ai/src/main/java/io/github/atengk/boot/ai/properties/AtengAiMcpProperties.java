package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic Model Context Protocol (MCP) 客户端强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
public class AtengAiMcpProperties {

    /**
     * 是否启用 MCP 客户端桥接基础设施
     */
    private boolean enabled = true;

    /**
     * 命名的远程 MCP 服务端端点拓扑 (key 为服务名称，例如 weather-server, filesystem-mcp)
     */
    private Map<String, McpServerConfig> servers = new LinkedHashMap<>();

    /**
     * 单个远程 MCP 服务端端点连接配置
     */
    @Data
    public static class McpServerConfig {
        /**
         * 远程 MCP 服务端 HTTP/SSE 端点 URL (例如 http://localhost:8080/mcp 或 https://mcp.internal.net/v1)
         */
        private String url;

        /**
         * 可选的鉴权令牌 API Key 或 Bearer Token
         */
        private String apiKey;

        /**
         * 远程通信连接与读取超时时间 (默认 30s)
         */
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * 是否在应用启动时自动预热执行 tools/list 发现工具
         */
        private boolean eagerInit = true;
    }
}
