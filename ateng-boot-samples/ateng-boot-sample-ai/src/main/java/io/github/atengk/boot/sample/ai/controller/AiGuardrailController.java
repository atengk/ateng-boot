package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.guardrail.AiGuardrail;
import io.github.atengk.boot.ai.guardrail.GuardrailResult;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiGuardrailInspectRequest;
import io.github.atengk.boot.sample.ai.vo.AiGuardrailInspectVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * AI 双向内容安全护栏示例控制器
 * 提供前置提示词注入拦截、后置大模型敏感词合规审查与端到端安全对话体验
 *
 * @author Ateng
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/sample/ai/guardrail")
public class AiGuardrailController {

    private final AiGuardrail guardrail;
    private final AiTemplate aiTemplate;

    public AiGuardrailController(AiGuardrail guardrail, AiTemplate aiTemplate) {
        this.guardrail = Objects.requireNonNull(guardrail, "AiGuardrail 不能为空");
        this.aiTemplate = Objects.requireNonNull(aiTemplate, "AiTemplate 不能为空");
    }

    /**
     * 前置提示词安全合规审查接口 (PRE_PROMPT)
     * 针对越狱、Prompt Injection 提示词注入及违禁词实施防卫
     *
     * @param request 审查请求 DTO
     * @return 护栏审查裁决结果 VO
     */
    @PostMapping("/inspect-prompt")
    public Result<AiGuardrailInspectVo> inspectPrompt(@Valid @RequestBody AiGuardrailInspectRequest request) {
        GuardrailResult result = guardrail.inspectPrompt(request.getText(), request.getContext());
        return Result.success(toVo(result));
    }

    /**
     * 后置模型回复文本安全合规审查接口 (POST_RESPONSE)
     * 针对模型生成结果中的违禁敏感词实施合规防卫与替换
     *
     * @param request 审查请求 DTO
     * @return 护栏审查裁决结果 VO
     */
    @PostMapping("/inspect-response")
    public Result<AiGuardrailInspectVo> inspectResponse(@Valid @RequestBody AiGuardrailInspectRequest request) {
        GuardrailResult result = guardrail.inspectResponse(request.getText(), request.getContext());
        return Result.success(toVo(result));
    }

    /**
     * 受护栏保护的端到端对话体验接口
     * 提示词若存在越狱注入风险，将由底层 AiTemplate + AiGuardrail 自动熔断并抛出 AI_CONTENT_RISK_DETECTED
     *
     * @param request 审查请求 DTO
     * @return 正常对话文本或安全替换结果
     */
    @PostMapping("/chat")
    public Result<String> chatWithGuardrail(@Valid @RequestBody AiGuardrailInspectRequest request) {
        String answer = aiTemplate.chat(request.getText());
        return Result.success(answer);
    }

    private AiGuardrailInspectVo toVo(GuardrailResult result) {
        return AiGuardrailInspectVo.builder()
                .allowed(result.isAllowed())
                .action(result.getAction() != null ? result.getAction().name() : null)
                .phase(result.getPhase() != null ? result.getPhase().name() : null)
                .matchedRule(result.getMatchedRule())
                .reason(result.getReason())
                .safeContent(result.getSafeContent())
                .build();
    }
}
