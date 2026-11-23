package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Model Context Protocol (MCP) 客户端桥接适配器 SPI
 * <p>
 * 遵循 Anthropic MCP 协议标准（基于 JSON-RPC 2.0），将企业现存或外部远程部署的 MCP 服务端工具清单、
 * 只读资源与提示词模板无缝适配至 Spring Boot 与 LangChain4j 生态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface McpClientAdapter {

    /**
     * 获取 MCP 服务端暴露并转换为 LangChain4j 标准沙箱执行器的工具映射字典
     *
     * @return 映射字典 (ToolSpecification -> ToolExecutor)
     */
    Map<ToolSpecification, ToolExecutor> getTools();

    /**
     * 直接通过名称调用指定的 MCP 远程工具
     *
     * @param toolName  工具名称
     * @param arguments 入参字典
     * @return 远程工具执行结果文本
     */
    String callTool(String toolName, Map<String, Object> arguments);

    /**
     * 重新发现并同步刷新远程 MCP 服务端暴露的工具列表
     */
    void refreshTools();

    /**
     * 获取当前 MCP 服务端标识名称
     *
     * @return 服务名称
     */
    String getServerName();

    /**
     * 查询远程 MCP 服务端暴露的只读资源清单 ({@code resources/list})
     *
     * @return 资源元数据列表（默认返回空列表）
     */
    default List<McpResource> listResources() {
        return Collections.emptyList();
    }

    /**
     * 读取指定 URI 对应的远程 MCP 资源内容 ({@code resources/read})
     *
     * @param uri 目标资源 URI
     * @return 资源文本内容（默认返回空串）
     */
    default String readResource(String uri) {
        return "";
    }

    /**
     * 查询远程 MCP 服务端暴露的提示词模板清单 ({@code prompts/list})
     *
     * @return 提示词模板列表（默认返回空列表）
     */
    default List<McpPrompt> listPrompts() {
        return Collections.emptyList();
    }

    /**
     * 按模板名称与入参渲染获取远程 MCP 提示词内容 ({@code prompts/get})
     *
     * @param name      提示词模板名称
     * @param arguments 模板渲染入参字典
     * @return 渲染后的提示词文本内容（默认返回空串）
     */
    default String getPrompt(String name, Map<String, Object> arguments) {
        return "";
    }
}
