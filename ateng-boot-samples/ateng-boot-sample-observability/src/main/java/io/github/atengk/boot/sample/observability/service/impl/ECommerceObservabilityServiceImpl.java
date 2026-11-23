package io.github.atengk.boot.sample.observability.service.impl;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.observability.annotation.ObservedMetric;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.github.atengk.boot.sample.observability.dto.BurstPressureRequest;
import io.github.atengk.boot.sample.observability.dto.CampaignMetricRequest;
import io.github.atengk.boot.sample.observability.dto.FulfillmentTrackRequest;
import io.github.atengk.boot.sample.observability.dto.LogisticsDispatchRequest;
import io.github.atengk.boot.sample.observability.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.observability.dto.OrderRiskCheckRequest;
import io.github.atengk.boot.sample.observability.dto.OrderSettleRequest;
import io.github.atengk.boot.sample.observability.dto.StockDeductRequest;
import io.github.atengk.boot.sample.observability.service.ECommerceObservabilityService;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 电商交易与履约可观测性业务服务实现类
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Service
@Slf4j
public class ECommerceObservabilityServiceImpl implements ECommerceObservabilityService {

    private final ThreadPoolTaskExecutor sampleTaskExecutor;
    private final MetricsTemplate metricsTemplate;
    private final AtomicInteger activeMerchants = new AtomicInteger(128);
    private final Queue<String> pendingFulfillmentQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, String> localCampaignCache = new ConcurrentHashMap<>();

    public ECommerceObservabilityServiceImpl(
            @Qualifier("sampleTaskExecutor") ThreadPoolTaskExecutor sampleTaskExecutor,
            MetricsTemplate metricsTemplate) {
        this.sampleTaskExecutor = sampleTaskExecutor;
        this.metricsTemplate = metricsTemplate;
    }

    @PostConstruct
    public void initGaugeMetrics() {
        pendingFulfillmentQueue.offer("FUL-20261116-001");
        pendingFulfillmentQueue.offer("FUL-20261116-002");
        localCampaignCache.put("CAMPAIGN-1116", "ACTIVE");
        localCampaignCache.put("CAMPAIGN-VIP", "ACTIVE");
        localCampaignCache.put("CAMPAIGN-NEW", "WARMUP");

        metricsTemplate.gauge("sample.merchants.active", activeMerchants, AtomicInteger::get, "region", "east");
        metricsTemplate.gaugeCollectionSize("sample.fulfillment.pending.queue.size", pendingFulfillmentQueue, "domain", "fulfillment");
        metricsTemplate.gaugeMapSize("sample.campaign.local.cache.size", localCampaignCache, "domain", "trade");
    }

    @Override
    public String recordOrderMetrics(OrderCreateRequest request) {
        BusinessAssert.notNull(request, "订单创建请求参数不能为空");
        // 编程式指标打点：Counter 计数、DistributionSummary 金额分布与 Timer 耗时记录
        metricsTemplate.increment("sample.orders", 1.0, "channel", request.getChannel());
        metricsTemplate.summary("sample.orders.amount", request.getAmount(), "channel", request.getChannel());
        metricsTemplate.record("sample.orders.process.time", Duration.ofMillis(25), "channel", request.getChannel());
        return "订单 " + request.getOrderId() + " 业务指标打点成功";
    }

    @Override
    @ObservedMetric(value = "sample.demo.observed", tags = {"tier", "api"}, percentiles = {0.95, 0.99})
    public String deductInventoryStock(StockDeductRequest request) {
        BusinessAssert.notNull(request, "库存扣减请求参数不能为空");
        // 声明式 AOP 切面自动统计执行耗时、分位数 (P95/P99) 及成功/失败计数
        return "商品 " + request.getProductId() + " 成功扣减库存 " + request.getCount() + " 件";
    }

    @Override
    public double getActiveMerchantCount() {
        return activeMerchants.get();
    }

    @Override
    public String dispatchLogisticsAsync(LogisticsDispatchRequest request) {
        BusinessAssert.notNull(request, "物流调度请求参数不能为空");
        // 异步调度运单履约任务，产生线程池负载
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, sampleTaskExecutor);
        return "运单 " + request.getTrackId() + " 已提交至受管线程池 sampleTaskExecutor";
    }

    @Override
    public String triggerBurstPressure(BurstPressureRequest request) {
        int count = (request != null && request.getTaskCount() != null) ? request.getTaskCount() : 70;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            // 提交超过 core(4) + queue(50) + max(8) 容量的并发阻塞任务，强制触发拒绝策略
            for (int i = 0; i < count; i++) {
                sampleTaskExecutor.execute(() -> {
                    try {
                        latch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } catch (RejectedExecutionException ignored) {
            // 预期由 TrackingRejectedExecutionHandler 拦截并累加计数器与发布事件
        } finally {
            latch.countDown();
        }
        return "已触发线程池并发高压与任务拒绝追踪";
    }

    @Override
    public String evaluateOrderRisk(OrderRiskCheckRequest request) {
        BusinessAssert.notNull(request, "订单风控校验请求参数不能为空");
        return metricsTemplate.record("sample.orders.risk.check.time", () -> {
            String decision = request.getAmount() >= 10000.0 ? "REVIEW" : "APPROVED";
            return "订单 " + request.getOrderId() + " 风控校验完成: " + decision;
        }, "channel", request.getChannel());
    }

    @Override
    public String trackFulfillmentStages(FulfillmentTrackRequest request) {
        BusinessAssert.notNull(request, "分段履约计时请求参数不能为空");
        Timer.Sample sample = metricsTemplate.startSample();
        try {
            return "订单 " + request.getOrderId() + " 在仓库 " + request.getWarehouseCode() + " 完成分段履约";
        } finally {
            metricsTemplate.stopSample(sample, "sample.fulfillment.duration", "warehouse", request.getWarehouseCode());
        }
    }

    @Override
    public Map<String, Integer> getCapacityGauges() {
        Map<String, Integer> snapshot = new LinkedHashMap<>(2);
        snapshot.put("pendingQueueSize", pendingFulfillmentQueue.size());
        snapshot.put("localCacheSize", localCampaignCache.size());
        return snapshot;
    }

    @Override
    public String recordCampaignMetric(CampaignMetricRequest request) {
        BusinessAssert.notNull(request, "促销活动指标请求参数不能为空");
        metricsTemplate.increment("sample.campaign.flash.sale", 1.0,
                "campaignId", request.getCampaignId(), "channel", request.getChannel());
        return "活动 " + request.getCampaignId() + " 指标打点成功";
    }

    @Override
    public int removeCampaignMetric(CampaignMetricRequest request) {
        BusinessAssert.notNull(request, "促销活动指标请求参数不能为空");
        return metricsTemplate.remove("sample.campaign.flash.sale",
                "campaignId", request.getCampaignId(), "channel", request.getChannel());
    }

    @Override
    @ObservedMetric(
            value = "sample.order.settle",
            tags = {"domain", "trade"},
            percentiles = {0.95, 0.99},
            histogram = true,
            slaMs = {50, 100, 500}
    )
    public String settleOrder(OrderSettleRequest request) {
        BusinessAssert.notNull(request, "订单结算请求参数不能为空");
        return "订单 " + request.getOrderId() + " 通过 " + request.getPaymentMethod() + " 结算成功";
    }
}
