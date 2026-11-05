package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.mcp.HttpMcpClientAdapter;
import io.github.atengk.boot.ai.mcp.McpClientAdapter;
import io.github.atengk.boot.ai.mcp.McpClientRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiDynamicToolRequest;
import io.github.atengk.boot.sample.ai.dto.AiMcpCallRequest;
import io.github.atengk.boot.sample.ai.tool.CalculatorTool;
import io.github.atengk.boot.sample.ai.vo.AiDynamicToolVo;
import io.github.atengk.boot.sample.ai.vo.AiMcpCallVo;
import io.github.atengk.boot.sample.ai.vo.AiMcpListVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 编程式动态工具与标准 MCP 客户端体验接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Slf4j
@RestController
@RequestMapping("/sample/ai/tools")
@RequiredArgsConstructor
public class AiToolsController {

    private final AiTemplate aiTemplate;
    private final CalculatorTool calculatorTool;
    private final ObjectProvider<McpClientRegistry> mcpClientRegistryProvider;

    /**
     * 编程式动态工具挂载对话体验 (零注解声明式，按需反射与沙箱执行)
     *
     * @param request 对话请求
     * @return 大模型结合工具执行结果生成的最终响应
     */
    @PostMapping("/chat-dynamic")
    public Result<AiDynamicToolVo> chatDynamic(@Valid @RequestBody AiDynamicToolRequest request) {
        String answer = aiTemplate.chatWithTools(request.getQuestion(), calculatorTool);

        List<String> availableTools = Arrays.stream(calculatorTool.getClass().getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(m -> calculatorTool.getClass().getSimpleName() + "#" + m.getName())
                .sorted()
                .toList();

        AiDynamicToolVo vo = AiDynamicToolVo.builder()
                .question(request.getQuestion())
                .answer(answer)
                .toolsUsed(availableTools)
                .build();
        return Result.success(vo);
    }

    /**
     * 查询当前注册的远程 MCP 客户端服务端与工具列表
     *
     * @return MCP 服务端概览与动态工具清单
     */
    @GetMapping("/mcp-list")
    public Result<AiMcpListVo> getMcpList() {
        McpClientRegistry registry = mcpClientRegistryProvider.getIfAvailable();
        if (registry == null) {
            AiMcpListVo vo = AiMcpListVo.builder()
                    .totalServers(0)
                    .servers(Collections.emptyList())
                    .build();
            return Result.success(vo);
        }

        Map<String, McpClientAdapter> adapters = registry.getAllAdapters();
        List<AiMcpListVo.ServerItem> serverItems = new ArrayList<>();

        adapters.forEach((name, adapter) -> {
            List<String> toolNames = Collections.emptyList();
            try {
                Map<ToolSpecification, ?> tools = adapter.getTools();
                if (tools != null) {
                    toolNames = tools.keySet().stream().map(ToolSpecification::name).toList();
                }
            } catch (Exception e) {
                log.warn("[AI-SAMPLE-TOOLS] 远程 MCP 服务 [{}] 工具清单发现失败: {}", adapter.getServerName(), e.getMessage());
            }

            String endpoint = adapter instanceof HttpMcpClientAdapter httpAdapter
                    ? httpAdapter.getEndpointUrl()
                    : "custom";

            serverItems.add(AiMcpListVo.ServerItem.builder()
                    .serverName(adapter.getServerName())
                    .url(endpoint)
                    .toolCount(toolNames.size())
                    .tools(toolNames)
                    .build());
        });

        AiMcpListVo vo = AiMcpListVo.builder()
                .totalServers(serverItems.size())
                .servers(serverItems)
                .build();
        return Result.success(vo);
    }

    /**
     * 调用执行指定远程 MCP 服务端暴露的标准工具
     *
     * @param request MCP 工具调用参数
     * @return 工具执行响应
     */
    @PostMapping("/mcp-call")
    public Result<AiMcpCallVo> callMcpTool(@Valid @RequestBody AiMcpCallRequest request) {
        McpClientRegistry registry = mcpClientRegistryProvider.getIfAvailable();
        if (registry == null || !registry.contains(request.getServerName())) {
            throw new BusinessException(AiResultCode.AI_MCP_CALL_FAILED,
                    "未找到已配置或注册的远程 MCP 服务端: " + request.getServerName());
        }

        McpClientAdapter adapter = registry.getAdapter(request.getServerName());
        long start = System.currentTimeMillis();
        String result = adapter.callTool(request.getToolName(), request.getArguments());
        long duration = System.currentTimeMillis() - start;

        AiMcpCallVo vo = AiMcpCallVo.builder()
                .serverName(request.getServerName())
                .toolName(request.getToolName())
                .result(result)
                .durationMs(duration)
                .build();
        return Result.success(vo);
    }
}
