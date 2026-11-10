package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.observability.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.observability.dto.StockDeductRequest;
import io.github.atengk.boot.sample.observability.service.ECommerceObservabilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
