package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.ratelimit.AiRateLimiter;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitChatRequest;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitRequest;
import io.github.atengk.boot.sample.ai.vo.AiRateLimitChatVo;
import io.github.atengk.boot.sample.ai.vo.AiRateLimitVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 微观突发滑动限流与配额防卫体验接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/ratelimit")
@RequiredArgsConstructor
public class AiRateLimitController {

    private final ObjectProvider<AiRateLimiter> rateLimiterProvider;
    private final AiTemplate aiTemplate;

    /**
     * 尝试获取 AI 吞吐限流与 Token 配额许可 (RPS & TPM 联合防御)
     *
     * @param request 限流检查参数
     * @return 限流决策结果
     */
    @PostMapping("/try-acquire")
    public Result<AiRateLimitVo> tryAcquire(@Valid @RequestBody AiRateLimitRequest request) {
        AiRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        int tokens = request.getTokens() != null ? request.getTokens() : 100;
        String clientKey = request.getTenantId() != null && !request.getTenantId().isBlank()
                ? request.getTenantId()
                : request.getApiKey();

        if (rateLimiter == null) {
            AiRateLimitVo vo = AiRateLimitVo.builder()
                    .allowed(true)
                    .clientKey(clientKey)
                    .remainingTokens(-1L)
                    .retryAfterMs(0L)
                    .message("限流组件未启用，默认直接放行")
                    .build();
            return Result.success(vo);
        }

        boolean allowed = rateLimiter.tryAcquire(request.getTenantId(), request.getModelName(), tokens);

        AiRateLimitVo vo = AiRateLimitVo.builder()
                .allowed(allowed)
                .clientKey(clientKey)
                .remainingTokens(allowed ? (long) tokens : 0L)
                .retryAfterMs(allowed ? 0L : 1000L)
                .message(allowed ? "请求通过突发滑动窗口与 TPM 配额核验" : "触发微观突发限流保护 (429 Too Many Requests)，请稍后重试")
                .build();
        return Result.success(vo);
    }

    /**
     * 受控限流大模型对话体验 (限流器守卫，超限自动抛出 AI_RATE_LIMIT_EXCEEDED 业务异常)
     *
     * @param request 限流对话参数
     * @return 最终对话结果
     */
    @PostMapping("/chat")
    public Result<AiRateLimitChatVo> chat(@Valid @RequestBody AiRateLimitChatRequest request) {
        AiRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        int tokens = request.getEstimatedTokens() != null ? request.getEstimatedTokens() : 100;

        if (rateLimiter != null) {
            boolean allowed = rateLimiter.tryAcquire(request.getTenantId(), request.getModelName(), tokens);
            if (!allowed) {
                throw new BusinessException(AiResultCode.AI_RATE_LIMIT_EXCEEDED,
                        String.format("租户 [%s] 针对模型 [%s] 触发微观突发流量限流保护 (HTTP 429)，请稍后重试",
                                request.getTenantId(), request.getModelName()));
            }
        }

        // 优先调用指定命名模型，若未注册独立从模型则安全降级使用主模型
        String reply;
        if (request.getModelName() != null && !"default".equalsIgnoreCase(request.getModelName())) {
            try {
                reply = aiTemplate.chatWithModel(request.getModelName(), request.getPrompt());
            } catch (BusinessException e) {
                if (e.getResultCode() == AiResultCode.AI_MODEL_NOT_FOUND) {
                    reply = aiTemplate.chat(request.getPrompt());
                } else {
                    throw e;
                }
            }
        } else {
            reply = aiTemplate.chat(request.getPrompt());
        }

        AiRateLimitChatVo vo = AiRateLimitChatVo.builder()
                .prompt(request.getPrompt())
                .reply(reply)
                .modelName(request.getModelName())
                .tenantId(request.getTenantId())
                .tokensUsed(tokens)
                .allowed(true)
                .message("请求成功通过突发频控与 Token 预算拦截并顺利完成大模型对话")
                .build();
        return Result.success(vo);
    }
}
