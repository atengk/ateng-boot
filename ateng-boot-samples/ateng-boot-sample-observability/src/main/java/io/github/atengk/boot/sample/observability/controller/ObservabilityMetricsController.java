package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.observability.dto.CampaignMetricRequest;
import io.github.atengk.boot.sample.observability.dto.FulfillmentTrackRequest;
import io.github.atengk.boot.sample.observability.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.observability.dto.OrderRiskCheckRequest;
import io.github.atengk.boot.sample.observability.dto.OrderSettleRequest;
import io.github.atengk.boot.sample.observability.dto.StockDeductRequest;
import io.github.atengk.boot.sample.observability.service.ECommerceObservabilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 业务性能度量领域控制器
 * <p>
 * 提供编程式指标打点（Counter/Summary/Timer）、声明式切面监控（@ObservedMetric）与 Gauge 动态采集接口
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/observability/metrics")
public class ObservabilityMetricsController {

    private final ECommerceObservabilityService observabilityService;

    public ObservabilityMetricsController(ECommerceObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /**
     * 编程式指标打点：记录订单创建计数、金额分布与执行耗时
     */
    @PostMapping("/order-create")
    public Result<String> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        String message = observabilityService.recordOrderMetrics(request);
        return Result.success(message);
    }

    /**
     * 声明式指标打点：利用 @ObservedMetric 切面记录库存扣减方法级耗时与分位数
     */
    @PostMapping("/stock-deduct")
    public Result<String> deductStock(@Valid @RequestBody StockDeductRequest request) {
        String message = observabilityService.deductInventoryStock(request);
        return Result.success(message);
    }

    /**
     * 动态 Gauge 指标采集：获取当前在线活跃商户数
     */
    @GetMapping("/merchant-active-count")
    public Result<Double> getActiveMerchantCount() {
        double count = observabilityService.getActiveMerchantCount();
        return Result.success(count);
    }

    /**
     * 函数式 Supplier 计时：订单风控校验耗时打点
     */
    @PostMapping("/risk-check")
    public Result<String> evaluateOrderRisk(@Valid @RequestBody OrderRiskCheckRequest request) {
        String message = observabilityService.evaluateOrderRisk(request);
        return Result.success(message);
    }

    /**
     * 手动分段采样计时：基于 startSample / stopSample 测量分段履约耗时
     */
    @PostMapping("/fulfillment-track")
    public Result<String> trackFulfillmentStages(@Valid @RequestBody FulfillmentTrackRequest request) {
        String message = observabilityService.trackFulfillmentStages(request);
        return Result.success(message);
    }

    /**
     * 集合与 Map 容量 Gauge 采集：获取待处理履约队列与本地活动缓存实时容量
     */
    @GetMapping("/capacity-gauges")
    public Result<Map<String, Integer>> getCapacityGauges() {
        Map<String, Integer> snapshot = observabilityService.getCapacityGauges();
        return Result.success(snapshot);
    }

    /**
     * 临时促销活动指标打点：记录临时大促活动计数
     */
    @PostMapping("/campaign-metric/record")
    public Result<String> recordCampaignMetric(@Valid @RequestBody CampaignMetricRequest request) {
        String message = observabilityService.recordCampaignMetric(request);
        return Result.success(message);
    }

    /**
     * 临时促销活动指标动态注销：清理指定活动与渠道维度下的指标避免维度残留
     */
    @PostMapping("/campaign-metric/remove")
    public Result<Integer> removeCampaignMetric(@Valid @RequestBody CampaignMetricRequest request) {
        int removedCount = observabilityService.removeCampaignMetric(request);
        return Result.success(removedCount);
    }

    /**
     * 声明式直方图与 SLO 耗时桶打点：订单结算（开启 histogram=true 与 slaMs={50,100,500}）
     */
    @PostMapping("/order-settle")
    public Result<String> settleOrder(@Valid @RequestBody OrderSettleRequest request) {
        String message = observabilityService.settleOrder(request);
        return Result.success(message);
    }
}
