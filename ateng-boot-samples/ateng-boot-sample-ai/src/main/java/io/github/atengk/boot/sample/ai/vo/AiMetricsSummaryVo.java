package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 可观测度量与风控监控大盘汇总视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMetricsSummaryVo {

    /**
     * 累计发起的大模型请求总数 (ateng.ai.requests.total)
     */
    private double totalRequests;

    /**
     * 成功的请求总数 (status=SUCCESS)
     */
    private double successRequests;

    /**
     * 异常失败的请求总数 (status=ERROR)
     */
    private double errorRequests;

    /**
     * 客户端主动取消/中断的请求总数 (status=CANCELLED)
     */
    private double cancelledRequests;

    /**
     * 累计消耗的 Token 总量 (ateng.ai.tokens.total, type=total)
     */
    private double totalTokens;

    /**
     * 其中保底估算的 Token 消耗总量 (estimated=true)
     */
    private double estimatedTokens;

    /**
     * 真实网关返回的 Token 消耗总量 (estimated=false)
     */
    private double realTokens;

    /**
     * 指定租户今日累计调用次数
     */
    private long tenantDailyRequests;

    /**
     * 指定租户今日累计 Token 消耗
     */
    private long tenantDailyTokens;

    /**
     * 当前配额计数器是否工作于 Redis 分布式集群模式
     */
    private boolean redisMode;
}
