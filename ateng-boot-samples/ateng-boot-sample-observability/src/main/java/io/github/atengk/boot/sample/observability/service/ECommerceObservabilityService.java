package io.github.atengk.boot.sample.observability.service;

import io.github.atengk.boot.sample.observability.dto.BurstPressureRequest;
import io.github.atengk.boot.sample.observability.dto.LogisticsDispatchRequest;
import io.github.atengk.boot.sample.observability.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.observability.dto.StockDeductRequest;

/**
 * 电商交易与履约可观测性业务服务接口
 *
 * @author Ateng
 * @since 2026-11-10
 */
public interface ECommerceObservabilityService {

    /**
     * 编程式指标打点：订单创建计数、金额分布与计时
     *
     * @param request 订单创建请求
     * @return 业务处理结果说明
     */
    String recordOrderMetrics(OrderCreateRequest request);

    /**
     * 声明式指标打点：库存扣减方法级耗时与分位数监控
     *
     * @param request 库存扣减请求
     * @return 业务处理结果说明
     */
    String deductInventoryStock(StockDeductRequest request);

    /**
     * 动态 Gauge 指标采集：获取在线活跃商户数
     *
     * @return 当前活跃商户数
     */
    double getActiveMerchantCount();

    /**
     * 异步物流推单：向受管线程池提交异步履约任务
     *
     * @param request 物流推单请求
     * @return 业务处理结果说明
     */
    String dispatchLogisticsAsync(LogisticsDispatchRequest request);

    /**
     * 线程池瞬时高压并发：触发线程池打满与拒绝策略追踪
     *
     * @param request 突发高压请求
     * @return 触发状态说明
     */
    String triggerBurstPressure(BurstPressureRequest request);
}
