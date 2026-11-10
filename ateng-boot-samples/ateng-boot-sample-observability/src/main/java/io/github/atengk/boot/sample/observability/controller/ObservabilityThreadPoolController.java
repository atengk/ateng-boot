package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.observability.dto.BurstPressureRequest;
import io.github.atengk.boot.sample.observability.dto.LogisticsDispatchRequest;
import io.github.atengk.boot.sample.observability.service.ECommerceObservabilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受管线程池度量与任务饱和拒绝领域控制器
 * <p>
 * 提供异步任务履约调度、高并发瞬时打满与 TrackingRejectedExecutionHandler 拒绝追踪验证接口
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/observability/thread-pool")
public class ObservabilityThreadPoolController {

    private final ECommerceObservabilityService observabilityService;

    public ObservabilityThreadPoolController(ECommerceObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /**
     * 触发异步物流履约任务，展示受管线程池动态度量与指标抓取
     */
    @PostMapping("/dispatch-logistics")
    public Result<String> dispatchLogistics(@Valid @RequestBody LogisticsDispatchRequest request) {
        String message = observabilityService.dispatchLogisticsAsync(request);
        return Result.success(message);
    }

    /**
     * 触发线程池瞬时打满拒绝，展示任务拒绝原子计数与 ThreadPoolRejectedEvent 广播
     */
    @PostMapping("/trigger-burst")
    public Result<String> triggerBurstPressure(@Valid @RequestBody BurstPressureRequest request) {
        String message = observabilityService.triggerBurstPressure(request);
        return Result.success(message);
    }
}
