package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;

/**
 * AI 微观突发流量滑动窗口限流器 SPI (RPS & TPM)
 * <p>
 * 支持基于每秒请求数 (Requests Per Second, RPS) 与每分钟 Token 吞吐量 (Tokens Per Minute, TPM)
 * 对多租户、多模型及全局微观突发流量实施细粒度流控，防御下游大模型 API 触发 429 封禁或网关雪崩。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface AiRateLimiter {

    /**
     * 尝试获取调用许可 (非阻塞)
     *
     * @param tenantId        租户标识 (可为 null 或空白)
     * @param modelName       请求的目标模型名称或别名
     * @param estimatedTokens 预估消耗的 Token 数
     * @return 若获取成功返回 true，若触发限流返回 false
     */
    boolean tryAcquire(String tenantId, String modelName, int estimatedTokens);

    /**
     * 尝试获取调用许可；若限流则直接抛出 AI_RATE_LIMIT_EXCEEDED 业务异常 (Fail-Fast)
     *
     * @param tenantId        租户标识
     * @param modelName       目标模型名称
     * @param estimatedTokens 预估 Token 数
     * @throws AiException 若触发限流则抛出
     */
    default void acquire(String tenantId, String modelName, int estimatedTokens) {
        if (!tryAcquire(tenantId, modelName, estimatedTokens)) {
            String target = (tenantId != null && !tenantId.isBlank())
                    ? String.format("租户 [%s] 调用模型 [%s]", tenantId.trim(), modelName)
                    : String.format("模型 [%s]", modelName);
            throw new AiException(AiResultCode.AI_RATE_LIMIT_EXCEEDED,
                    String.format("[AI-RATE-LIMIT] %s 触发微观突发流量限流保护 (RPS 或 TPM 超过阈值)，请稍后重试", target));
        }
    }

    /**
     * 判断当前限流器是否基于分布式集群实现
     *
     * @return 分布式集群模式返回 true，单机内存模式返回 false
     */
    boolean isDistributed();
}
