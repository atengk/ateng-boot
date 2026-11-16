package io.github.atengk.boot.sample.observability.service;

import io.github.atengk.boot.sample.observability.dto.BurstPressureRequest;
import io.github.atengk.boot.sample.observability.dto.CampaignMetricRequest;
import io.github.atengk.boot.sample.observability.dto.FulfillmentTrackRequest;
import io.github.atengk.boot.sample.observability.dto.LogisticsDispatchRequest;
import io.github.atengk.boot.sample.observability.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.observability.dto.OrderRiskCheckRequest;
import io.github.atengk.boot.sample.observability.dto.OrderSettleRequest;
import io.github.atengk.boot.sample.observability.dto.StockDeductRequest;

import java.util.Map;

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

    /**
     * 订单风控校验：基于 MetricsTemplate.record(Supplier) 进行函数式环绕计时
     *
     * @param request 订单风控校验请求
     * @return 风控校验结果说明
     */
    String evaluateOrderRisk(OrderRiskCheckRequest request);

    /**
     * 分段履约计时：基于 startSample / stopSample 跨步骤测量耗时
     *
     * @param request 履约分段计时请求
     * @return 履约执行结果说明
     */
    String trackFulfillmentStages(FulfillmentTrackRequest request);

    /**
     * 容量 Gauge 采集：获取待处理履约队列与本地活动缓存实时容量快照
     *
     * @return 队列与缓存容量快照
     */
    Map<String, Integer> getCapacityGauges();

    /**
     * 临时促销活动指标打点：记录大促活动计数器
     *
     * @param request 临时促销活动请求
     * @return 打点结果说明
     */
    String recordCampaignMetric(CampaignMetricRequest request);

    /**
     * 临时促销活动指标动态注销：调用 MetricsTemplate.remove 清理指定标签组合的指标
     *
     * @param request 临时促销活动请求
     * @return 实际注销移除的 Meter 数量
     */
    int removeCampaignMetric(CampaignMetricRequest request);

    /**
     * 订单结算：基于 @ObservedMetric 开启可聚合直方图与 SLO 耗时桶采集
     *
     * @param request 订单结算请求
     * @return 结算结果说明
     */
    String settleOrder(OrderSettleRequest request);
}
