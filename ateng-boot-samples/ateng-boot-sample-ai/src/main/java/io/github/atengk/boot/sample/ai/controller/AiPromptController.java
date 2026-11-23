package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.prompt.PromptDefinition;
import io.github.atengk.boot.ai.prompt.PromptRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.agent.PromptManagedAdvisorAgent;
import io.github.atengk.boot.sample.ai.dto.AiPromptAgentChatRequest;
import io.github.atengk.boot.sample.ai.dto.AiPromptRegisterRequest;
import io.github.atengk.boot.sample.ai.dto.AiPromptRenderRequest;
import io.github.atengk.boot.sample.ai.vo.AiChatResponseVo;
import io.github.atengk.boot.sample.ai.vo.AiPromptDefinitionVo;
import io.github.atengk.boot.sample.ai.vo.AiPromptRenderVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 动态提示词资产托管与版本路由体验控制器
 * <p>
 * 提供提示词清单查询、按版本/标签（prod/canary）路由渲染与 strictValidation Fail-Fast 校验、
 * 基于 promptCode 发起对话、运行期动态注册/热刷新及声明式 @AiAgent(promptCode) 验证接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Validated
@RestController
@RequestMapping("/sample/ai/prompt")
@RequiredArgsConstructor
public class AiPromptController {

    private final PromptRegistry promptRegistry;
    private final AiTemplate aiTemplate;
    private final PromptManagedAdvisorAgent promptManagedAdvisorAgent;

    /**
     * 查询当前注册表中托管的全部提示词定义清单
     *
     * @return 提示词定义 VO 列表
     */
    @GetMapping("/list")
    public Result<List<AiPromptDefinitionVo>> listDefinitions() {
        List<AiPromptDefinitionVo> vos = promptRegistry.listDefinitions().stream()
                .map(AiPromptController::toVo)
                .toList();
        return Result.success(vos);
    }

    /**
     * 按编码及可选版本或发布标签解析提示词定义详情
     *
     * @param code           提示词编码
     * @param versionOrLabel 可选版本号或发布标签（如 prod / canary）
     * @return 提示词定义 VO
     */
    @GetMapping("/detail")
    public Result<AiPromptDefinitionVo> getDefinition(
            @RequestParam("code") @NotBlank(message = "提示词编码 code 不能为空") String code,
            @RequestParam(name = "versionOrLabel", required = false) String versionOrLabel) {
        PromptDefinition def = StringUtils.hasText(versionOrLabel)
                ? promptRegistry.get(code, versionOrLabel)
                : promptRegistry.get(code);
        return Result.success(toVo(def));
    }

    /**
     * 按编码与版本/标签渲染提示词模板（支持 strict=true 严格校验模式 Fail-Fast 拦截缺失变量）
     *
     * @param request 渲染请求 DTO
     * @return 渲染结果 VO
     */
    @PostMapping("/render")
    public Result<AiPromptRenderVo> render(@Valid @RequestBody AiPromptRenderRequest request) {
        PromptDefinition def = StringUtils.hasText(request.getVersionOrLabel())
                ? promptRegistry.get(request.getCode(), request.getVersionOrLabel())
                : promptRegistry.get(request.getCode());
        Map<String, Object> vars = request.getVariables() != null ? request.getVariables() : Collections.emptyMap();

        String rendered = request.isStrict()
                ? (StringUtils.hasText(request.getVersionOrLabel())
                        ? promptRegistry.renderStrict(request.getCode(), request.getVersionOrLabel(), vars)
                        : promptRegistry.renderStrict(request.getCode(), vars))
                : (StringUtils.hasText(request.getVersionOrLabel())
                        ? promptRegistry.render(request.getCode(), request.getVersionOrLabel(), vars)
                        : promptRegistry.render(request.getCode(), vars));

        return Result.success(AiPromptRenderVo.builder()
                .code(def.getCode())
                .version(def.getVersion())
                .label(def.getLabel())
                .strict(request.isStrict())
                .renderedContent(rendered)
                .build());
    }

    /**
     * 通过 promptCode 动态渲染提示词并发起大模型对话
     *
     * @param request 渲染与对话请求 DTO
     * @return 模型对话响应 VO
     */
    @PostMapping("/chat")
    public Result<AiChatResponseVo> chatByPromptCode(@Valid @RequestBody AiPromptRenderRequest request) {
        Map<String, Object> vars = request.getVariables() != null ? request.getVariables() : Collections.emptyMap();
        String reply = aiTemplate.chatByPromptCode(request.getModel(), request.getCode(), request.getVersionOrLabel(), vars);
        String resolvedModel = StringUtils.hasText(request.getModel()) ? request.getModel().trim() : "default";
        return Result.success(new AiChatResponseVo(reply, resolvedModel));
    }

    /**
     * 运行期动态注册或热更新提示词版本
     *
     * @param request 注册请求 DTO
     * @return 注册生效后的定义 VO
     */
    @PostMapping("/register")
    public Result<AiPromptDefinitionVo> register(@Valid @RequestBody AiPromptRegisterRequest request) {
        PromptDefinition def = PromptDefinition.builder()
                .code(request.getCode())
                .version(request.getVersion())
                .label(request.getLabel())
                .content(request.getContent())
                .requiredVariables(request.getRequiredVariables())
                .description(request.getDescription())
                .build();
        promptRegistry.register(def);
        return Result.success(toVo(def));
    }

    /**
     * 触发外部提示词提供者热刷新（可选指定单个 code 定向刷新或全量刷新）
     *
     * @param code 可选提示词编码
     * @return 刷新后注册表中的定义总数
     */
    @PostMapping("/refresh")
    public Result<Integer> refresh(@RequestParam(name = "code", required = false) String code) {
        if (StringUtils.hasText(code)) {
            promptRegistry.refresh(code);
        } else {
            promptRegistry.refreshAll();
        }
        return Result.success(promptRegistry.listDefinitions().size());
    }

    /**
     * 调用绑定 @AiAgent(promptCode = "advisor-system") 的声明式智能体接口
     *
     * @param request 问答请求 DTO
     * @return 智能体回答结果 VO
     */
    @PostMapping("/agent-chat")
    public Result<AiChatResponseVo> agentChat(@Valid @RequestBody AiPromptAgentChatRequest request) {
        String reply = promptManagedAdvisorAgent.advise(request.getQuestion());
        return Result.success(new AiChatResponseVo(reply, "advisor-system"));
    }

    private static AiPromptDefinitionVo toVo(PromptDefinition def) {
        return AiPromptDefinitionVo.builder()
                .code(def.getCode())
                .version(def.getVersion())
                .label(def.getLabel())
                .content(def.getContent())
                .requiredVariables(def.getRequiredVariables())
                .description(def.getDescription())
                .build();
    }
}
