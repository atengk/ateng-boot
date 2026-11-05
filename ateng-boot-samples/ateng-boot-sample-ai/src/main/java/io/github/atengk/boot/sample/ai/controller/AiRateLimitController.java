package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.ratelimit.AiRateLimiter;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRateLimitRequest;
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
                .remainingTokens(allowed ? 1000L : 0L)
                .retryAfterMs(allowed ? 0L : 1000L)
                .message(allowed ? "请求通过突发滑动窗口与 TPM 配额核验" : "触发微观突发限流保护 (429 Too Many Requests)，请稍后重试")
                .build();
        return Result.success(vo);
    }
}
