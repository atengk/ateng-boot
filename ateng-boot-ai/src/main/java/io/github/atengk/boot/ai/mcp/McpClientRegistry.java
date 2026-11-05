package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程 MCP 客户端适配器受管注册中心
 * <p>
 * 集中管理所有已配置和动态注册的远程 MCP 服务端客户端适配器 {@link McpClientAdapter}，
 * 支持按服务名称检索、聚合全量动态工具清单，供编程式或声明式动态工具调用直接注入。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    private final Map<String, McpClientAdapter> adapters = new ConcurrentHashMap<>();

    public McpClientRegistry() {
    }

    /**
     * 注册 MCP 客户端适配器
     *
     * @param serverName 服务端唯一名称
     * @param adapter    客户端适配器实例
     */
    public void register(String serverName, McpClientAdapter adapter) {
        Assert.hasText(serverName, "serverName 不能为空");
        Objects.requireNonNull(adapter, "adapter 不能为空");
        String key = serverName.trim();
        adapters.put(key, adapter);
        log.info("[AI-MCP] 注册远程 MCP 适配器: [{}] -> {}", key, adapter.getClass().getSimpleName());
    }

    /**
     * 注销指定名称的 MCP 客户端适配器
     *
     * @param serverName 服务端唯一名称
     * @return 被移除的适配器实例，若不存在返回 null
     */
    public McpClientAdapter unregister(String serverName) {
        if (!StringUtils.hasText(serverName)) {
            return null;
        }
        return adapters.remove(serverName.trim());
    }

    /**
     * 获取指定名称的 MCP 客户端适配器
     *
     * @param serverName 服务端唯一名称
     * @return 对应的适配器实例，若未注册返回 null
     */
    public McpClientAdapter getAdapter(String serverName) {
        if (!StringUtils.hasText(serverName)) {
            return null;
        }
        return adapters.get(serverName.trim());
    }

    /**
     * 判断是否已注册指定名称的 MCP 服务端
     *
     * @param serverName 服务端名称
     * @return 是否存在
     */
    public boolean contains(String serverName) {
        return StringUtils.hasText(serverName) && adapters.containsKey(serverName.trim());
    }

    /**
     * 获取当前所有已注册的 MCP 客户端适配器映射
     *
     * @return 服务名称到适配器的不可变映射
     */
    public Map<String, McpClientAdapter> getAllAdapters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(adapters));
    }

    /**
     * 聚合所有已注册 MCP 服务端暴露的可执行工具清单
     *
     * @return 全量工具规格到执行器的映射字典
     */
    public Map<ToolSpecification, ToolExecutor> getAllTools() {
        if (adapters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ToolSpecification, ToolExecutor> aggregatedTools = new LinkedHashMap<>();
        for (McpClientAdapter adapter : adapters.values()) {
            Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
            if (tools != null && !tools.isEmpty()) {
                aggregatedTools.putAll(tools);
            }
        }
        return Collections.unmodifiableMap(aggregatedTools);
    }

    /**
     * 聚合指定若干个 MCP 服务端暴露的可执行工具清单
     *
     * @param serverNames 指定的服务端名称列表
     * @return 工具规格到执行器的映射字典
     */
    public Map<ToolSpecification, ToolExecutor> getTools(String... serverNames) {
        if (serverNames == null || serverNames.length == 0 || adapters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ToolSpecification, ToolExecutor> aggregatedTools = new LinkedHashMap<>();
        for (String name : serverNames) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            McpClientAdapter adapter = adapters.get(name.trim());
            if (adapter != null) {
                Map<ToolSpecification, ToolExecutor> tools = adapter.getTools();
                if (tools != null && !tools.isEmpty()) {
                    aggregatedTools.putAll(tools);
                }
            }
        }
        return Collections.unmodifiableMap(aggregatedTools);
    }

    /**
     * 判断注册中心是否为空
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    /**
     * 获取当前已注册的 MCP 服务端数量
     *
     * @return 注册数量
     */
    public int size() {
        return adapters.size();
    }
}
