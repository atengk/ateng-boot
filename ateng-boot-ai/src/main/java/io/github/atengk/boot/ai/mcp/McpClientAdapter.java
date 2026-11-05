package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;

/**
 * Anthropic Model Context Protocol (MCP) 客户端桥接适配器 SPI
 * <p>
 * 遵循 Anthropic MCP 协议标准（基于 JSON-RPC 2.0），将企业现存或外部远程部署的 MCP 服务端工具清单
 * 无缝转换为 LangChain4j 的 {@link ToolSpecification} 与 {@link ToolExecutor} 沙箱执行器映射，
 * 支持在编程式函数调用与声明式智能体中即插即用。
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
}
