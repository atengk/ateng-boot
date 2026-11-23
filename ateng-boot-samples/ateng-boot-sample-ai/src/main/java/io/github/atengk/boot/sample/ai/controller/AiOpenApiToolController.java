package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.github.atengk.boot.ai.openapi.OpenApiToolAdapter;
import io.github.atengk.boot.ai.openapi.OpenApiToolProperties;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.OpenApiChatRequest;
import io.github.atengk.boot.sample.ai.dto.OpenApiExecuteRequest;
import io.github.atengk.boot.sample.ai.vo.OpenApiChatVo;
import io.github.atengk.boot.sample.ai.vo.OpenApiToolVo;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业 OpenAPI / Swagger 契约转受管沙箱工具体验控制器
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/sample/ai/agent/openapi-tools")
@RequiredArgsConstructor
public class AiOpenApiToolController {

    private final AiTemplate aiTemplate;

    @Value("${server.port:8080}")
    private int serverPort;

    private volatile OpenApiToolAdapter openApiToolAdapter;

    @PostConstruct
    public void init() {
        initAdapter("openapi/order-service-api.json", null);
    }

    /**
     * 初始化或重新加载 OpenAPI 工具适配器
     *
     * @param resourcePath 类路径资源
     * @param customBaseUrl 自定义基地址（可选）
     */
    public synchronized void initAdapter(String resourcePath, String customBaseUrl) {
        String baseUrl = StringUtils.hasText(customBaseUrl) ? customBaseUrl : "http://localhost:" + serverPort;
        OpenApiToolProperties properties = OpenApiToolProperties.builder()
                .name("order-management-service")
                .baseUrl(baseUrl)
                .propagateContext(true)
                .build();
        this.openApiToolAdapter = OpenApiToolAdapter.fromClasspath(resourcePath, properties);
        log.info("[AI-OPENAPI-SAMPLE] 成功加载 OpenAPI 契约: {} -> baseUrl: {}", resourcePath, baseUrl);
    }

    /**
     * 允许外部（如单测或上下文）替换适配器实例
     *
     * @param adapter 适配器实例
     */
    public void setOpenApiToolAdapter(OpenApiToolAdapter adapter) {
        this.openApiToolAdapter = adapter;
    }

    /**
     * 获取当前挂载的 OpenAPI 契约转转工具规格列表
     *
     * @return 工具规格摘要清单
     */
    @GetMapping("/specs")
    public Result<List<OpenApiToolVo>> getSpecs() {
        if (openApiToolAdapter == null) {
            return Result.success(new ArrayList<>());
        }

        List<ToolSpecification> specs = openApiToolAdapter.getToolSpecifications();
        List<OpenApiToolVo> list = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            List<String> paramNames = new ArrayList<>();
            if (spec.parameters() != null && spec.parameters().properties() != null) {
                paramNames.addAll(spec.parameters().properties().keySet());
            }

            list.add(OpenApiToolVo.builder()
                    .operationId(spec.name())
                    .summary(spec.description())
                    .description(spec.description())
                    .parameters(paramNames)
                    .build());
        }

        return Result.success(list);
    }

    /**
     * 直接通过 operationId 与参数字典调用目标受管 OpenAPI 工具
     *
     * @param request 工具调用请求参数
     * @return 目标接口返回的原始响应 JSON
     */
    @PostMapping("/execute")
    public Result<String> execute(@Valid @RequestBody OpenApiExecuteRequest request) {
        if (openApiToolAdapter == null) {
            init();
        }

        String result = openApiToolAdapter.execute(request.getOperationId(), request.getArguments());
        return Result.success(result);
    }

    /**
     * 携带 OpenAPI 工具发起大模型对话 (Function Calling 自动触发与反思自愈)
     *
     * @param request 对话提示词请求
     * @return 大模型结合工具执行结果生成的最终响应
     */
    @PostMapping("/chat")
    public Result<OpenApiChatVo> chat(@Valid @RequestBody OpenApiChatRequest request) {
        if (openApiToolAdapter == null) {
            init();
        }

        // 直接挂载 OpenAPI 适配器发起受管工具对话
        String answer = aiTemplate.chatWithTools(request.getPrompt(), openApiToolAdapter);

        List<String> toolNames = openApiToolAdapter.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .toList();

        return Result.success(OpenApiChatVo.builder()
                .response(answer)
                .toolsAvailable(toolNames)
                .build());
    }

    /**
     * 重新解析并热重载 OpenAPI 契约工具映射表
     *
     * @return 重载完成提示
     */
    @PostMapping("/reload")
    public Result<String> reload() {
        if (openApiToolAdapter != null) {
            openApiToolAdapter.reload();
            int count = openApiToolAdapter.getTools().size();
            return Result.success("OpenAPI 契约工具重载成功，当前可用工具数量: " + count);
        }
        init();
        return Result.success("OpenAPI 契约工具初始化成功");
    }
}
