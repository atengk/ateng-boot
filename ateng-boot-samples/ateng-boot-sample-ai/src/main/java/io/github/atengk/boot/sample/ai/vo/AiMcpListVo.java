package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 远程 MCP 客户端注册状态与工具列表视图对象
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMcpListVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 注册的 MCP 服务端总数
     */
    private int totalServers;

    /**
     * 服务端列表详情
     */
    @Builder.Default
    private List<ServerItem> servers = Collections.emptyList();

    /**
     * 单个 MCP 服务端信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 服务端标识名称
         */
        private String serverName;

        /**
         * 远程连接端点 URL
         */
        private String url;

        /**
         * 暴露的工具总数
         */
        private int toolCount;

        /**
         * 工具名称列表
         */
        @Builder.Default
        private List<String> tools = Collections.emptyList();
    }
}
