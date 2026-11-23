package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.service.TokenStream;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import io.github.atengk.boot.ai.streaming.SseTokenStreamBridge;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.ai.agent.FinancialAuditAgent;
import io.github.atengk.boot.sample.ai.dto.FinancialAuditRequest;
import io.github.atengk.boot.sample.ai.tool.OrderTools;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;

/**
 * 智能体多通道流式过程可观测控制器
 * <p>
 * 提供多通道 SSE 打字机流式推流体验接口，支持向前端实时下发思考链 (reasoning/agent_thought)、
 * 工具调用 (tool_call)、工具返回 (tool_result) 与回答正文 (text) 全流程状态事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/agent/stream")
@RequiredArgsConstructor
public class AiAgentStreamingController {

    private final FinancialAuditAgent financialAuditAgent;
    private final OrderTools orderTools;
    private final AiTemplate aiTemplate;
    private final ObjectProvider<ActiveStreamRegistry> activeStreamRegistryProvider;

    /**
     * 声明式智能体全维多通道过程流式推流
     * <p>
     * 前端可通过 EventSource 监听 {@code tool_call}、{@code tool_result}、{@code reasoning}、{@code text}、{@code done} 事件。
     * </p>
     *
     * @param orderId 待审计的订单号
     * @param remarks 可选审计备注
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/audit", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter auditStream(
            @RequestParam(name = "orderId", defaultValue = "ORD_20261103001") @NotBlank(message = "orderId 不能为空") String orderId,
            @RequestParam(name = "remarks", required = false) String remarks) {
        log.info("[AGENT-STREAM-HTTP] 接收到智能体多通道过程推流请求: orderId={}", orderId);
        SseEmitter emitter = new SseEmitter(AiTemplate.DEFAULT_SSE_TIMEOUT.toMillis());
        FinancialAuditRequest request = new FinancialAuditRequest(orderId, StringUtils.hasText(remarks) ? remarks : "全维过程流式审计");

        TokenStream tokenStream = financialAuditAgent.auditStream(request);
        String streamId = IdUtil.fastSimpleUUID();
        ActiveStreamRegistry registry = activeStreamRegistryProvider.getIfAvailable();

        SseTokenStreamBridge.pipe(tokenStream, emitter, streamId, registry);
        return emitter;
    }

    /**
     * 编程式动态工具多通道过程流式推流
     *
     * @param prompt 用户提示词
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/tools", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter toolsStream(
            @RequestParam(name = "prompt", defaultValue = "请帮我查询订单 ORD_20261103001 的详情并给出审计意见") String prompt) {
        log.info("[AGENT-STREAM-HTTP] 接收到编程式动态工具过程流式请求: prompt={}", prompt);
        SseEmitter emitter = new SseEmitter(AiTemplate.DEFAULT_SSE_TIMEOUT.toMillis());
        aiTemplate.streamWithTools(prompt, Collections.singletonList(orderTools), emitter);
        return emitter;
    }

    /**
     * 深度思考链提炼打字机推流
     *
     * @param prompt 推理提示词
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/reasoning", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter reasoningStream(
            @RequestParam(name = "prompt", defaultValue = "9.11 和 9.9 哪个大？请详细推理后给出最终结论。") String prompt) {
        log.info("[AGENT-STREAM-HTTP] 接收到思考链推流请求: prompt={}", prompt);
        return aiTemplate.stream(prompt);
    }
}
