package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.observability.AtengAiObservabilityListener;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;
import io.github.atengk.boot.ai.quota.AiQuotaChecker;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiQuotaTestRequestDto;
import io.github.atengk.boot.sample.ai.dto.AiStreamFallbackRequestDto;
import io.github.atengk.boot.sample.ai.vo.AiMetricsSummaryVo;
import io.github.atengk.boot.sample.ai.vo.AiQuotaTestVo;
import io.github.atengk.boot.sample.ai.vo.AiStreamFallbackVo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 云原生度量、配额风控与保底估算体验控制器
 * <p>
 * 提供 Micrometer 黄金指标大盘探查、多租户配额熔断自验与流式保底估算纯函数验证端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/metrics")
@RequiredArgsConstructor
public class AiMetricsController {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final ObjectProvider<AiQuotaChecker> quotaCheckerProvider;

    /**
     * 探查 AI 云原生度量黄金指标大盘与配额状态汇总
     *
     * @param tenantId 查询的租户标识 (默认 default)
     * @return 黄金指标与租户配额视图 VO
     */
    @GetMapping("/summary")
    public Result<AiMetricsSummaryVo> getMetricsSummary(
            @RequestParam(name = "tenantId", required = false, defaultValue = "default") String tenantId) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        AiQuotaChecker checker = quotaCheckerProvider.getIfAvailable();

        double totalRequests = 0.0;
        double successRequests = 0.0;
        double errorRequests = 0.0;
        double cancelledRequests = 0.0;
        double totalTokens = 0.0;
        double estimatedTokens = 0.0;
        double realTokens = 0.0;

        if (registry != null) {
            totalRequests = registry.find(AtengAiObservabilityListener.METRIC_REQUESTS_TOTAL)
                    .counters().stream().mapToDouble(Counter::count).sum();
            successRequests = registry.find(AtengAiObservabilityListener.METRIC_REQUESTS_TOTAL)
                    .tag("status", "SUCCESS")
                    .counters().stream().mapToDouble(Counter::count).sum();
            errorRequests = registry.find(AtengAiObservabilityListener.METRIC_REQUESTS_TOTAL)
                    .tag("status", "ERROR")
                    .counters().stream().mapToDouble(Counter::count).sum();
            cancelledRequests = registry.find(AtengAiObservabilityListener.METRIC_REQUESTS_TOTAL)
                    .tag("status", "CANCELLED")
                    .counters().stream().mapToDouble(Counter::count).sum();

            totalTokens = registry.find(AtengAiObservabilityListener.METRIC_TOKENS_TOTAL)
                    .tag("type", "total")
                    .counters().stream().mapToDouble(Counter::count).sum();
            estimatedTokens = registry.find(AtengAiObservabilityListener.METRIC_TOKENS_TOTAL)
                    .tag("estimated", "true")
                    .tag("type", "total")
                    .counters().stream().mapToDouble(Counter::count).sum();
            realTokens = registry.find(AtengAiObservabilityListener.METRIC_TOKENS_TOTAL)
                    .tag("estimated", "false")
                    .tag("type", "total")
                    .counters().stream().mapToDouble(Counter::count).sum();
        }

        long tenantRequests = 0L;
        long tenantTokens = 0L;
        boolean redisMode = false;

        if (checker != null) {
            tenantRequests = checker.getDailyRequests(tenantId);
            tenantTokens = checker.getDailyTokens(tenantId);
            redisMode = checker.isDistributed();
        }

        AiMetricsSummaryVo vo = AiMetricsSummaryVo.builder()
                .totalRequests(totalRequests)
                .successRequests(successRequests)
                .errorRequests(errorRequests)
                .cancelledRequests(cancelledRequests)
                .totalTokens(totalTokens)
                .estimatedTokens(estimatedTokens)
                .realTokens(realTokens)
                .tenantDailyRequests(tenantRequests)
                .tenantDailyTokens(tenantTokens)
                .redisMode(redisMode)
                .build();

        return Result.success(vo);
    }

    /**
     * 多租户配额风控自验端点
     * <p>
     * 校验租户配额，并在未超额时累计消耗；若超出阈值直接抛出业务异常触发 HTTP 200 业务错误码拦截。
     * </p>
     *
     * @param request 配额自验入参
     * @return 当前租户配额消耗状态
     */
    @PostMapping("/quota/test")
    public Result<AiQuotaTestVo> testQuota(@Valid @RequestBody AiQuotaTestRequestDto request) {
        AiQuotaChecker checker = quotaCheckerProvider.getIfAvailable();
        if (checker != null) {
            // 1. 前置配额拦截校验 (超额即刻 Fail-Fast 抛出 AiException)
            checker.checkQuota(request.getTenantId(), request.getModel(), request.getPromptTokens());

            // 2. 若非仅检查模式，则累计记录调用消耗
            if (!request.isCheckOnly()) {
                checker.recordUsage(request.getTenantId(), request.getModel(), request.getTotalTokens());
            }
        }

        long currentDailyRequests = 0L;
        long currentDailyTokens = 0L;
        if (checker != null) {
            currentDailyRequests = checker.getDailyRequests(request.getTenantId());
            currentDailyTokens = checker.getDailyTokens(request.getTenantId());
        }

        AiQuotaTestVo vo = AiQuotaTestVo.builder()
                .status("PASS")
                .tenantId(request.getTenantId())
                .currentDailyRequests(currentDailyRequests)
                .currentDailyTokens(currentDailyTokens)
                .build();

        return Result.success(vo);
    }

    /**
     * 流式 Token 保底估算纯函数体验端点
     *
     * @param request 待估算文本入参
     * @return 预估的 TokenUsage 详情
     */
    @PostMapping("/stream-fallback")
    public Result<AiStreamFallbackVo> testStreamFallback(@Valid @RequestBody AiStreamFallbackRequestDto request) {
        TokenUsage usage = FallbackTokenEstimator.estimate(request.getPrompt(), request.getResponseText());

        AiStreamFallbackVo vo = AiStreamFallbackVo.builder()
                .prompt(request.getPrompt())
                .responseText(request.getResponseText())
                .promptTokens(usage.inputTokenCount())
                .completionTokens(usage.outputTokenCount())
                .totalTokens(usage.totalTokenCount())
                .build();

        return Result.success(vo);
    }
}
