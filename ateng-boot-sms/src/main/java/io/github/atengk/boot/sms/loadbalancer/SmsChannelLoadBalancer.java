package io.github.atengk.boot.sms.loadbalancer;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 短信渠道平滑加权轮询负载均衡器 (Smooth Weighted Round-Robin)
 * 对标 Dromara SMS4J SmsLoad 算法内核，基于各渠道静态权重与动态当前权重实现高并发下平滑无抖动的请求分流
 * 深度联动 SmsChannelCircuitBreaker 熔断器，自动过滤处于开路熔断 (OPEN) 态的故障渠道
 * 当所有渠道均处于熔断态时，执行保底按权重选路防无路可用
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
public class SmsChannelLoadBalancer {

    @Getter
    private final SmsChannelRegistry channelRegistry;

    @Getter
    private final SmsChannelCircuitBreaker circuitBreaker;

    /**
     * 维护各渠道动态当前权重 (channelName -> currentWeight)
     */
    private final ConcurrentHashMap<String, AtomicInteger> currentWeights = new ConcurrentHashMap<>();

    public SmsChannelLoadBalancer(SmsChannelRegistry channelRegistry, SmsChannelCircuitBreaker circuitBreaker) {
        this.channelRegistry = channelRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    public SmsChannelLoadBalancer(SmsChannelRegistry channelRegistry) {
        this(channelRegistry, null);
    }

    /**
     * 在所有已注册短信渠道中执行平滑加权轮询选路
     *
     * @return 选中的短信渠道 SmsChannel，若无任何已注册渠道则返回 null
     */
    public synchronized SmsChannel selectChannel() {
        return selectChannel(null);
    }

    /**
     * 在指定的候选短信渠道名称列表中执行平滑加权轮询选路
     *
     * @param candidateNames 候选渠道名称列表（可为 null 或空，表示全量已注册渠道）
     * @return 选中的短信渠道 SmsChannel，若无任何匹配渠道则返回 null
     */
    public synchronized SmsChannel selectChannel(List<String> candidateNames) {
        if (channelRegistry == null) {
            return null;
        }

        List<SmsChannel> allCandidates;
        if (candidateNames != null && !candidateNames.isEmpty()) {
            allCandidates = candidateNames.stream()
                    .map(channelRegistry::get)
                    .filter(Objects::nonNull)
                    .toList();
        } else {
            allCandidates = channelRegistry.getAll();
        }

        if (allCandidates == null || allCandidates.isEmpty()) {
            return null;
        }

        if (allCandidates.size() == 1) {
            return allCandidates.get(0);
        }

        // 1. 过滤熔断开路状态 (OPEN) 的渠道
        List<SmsChannel> healthyCandidates = allCandidates;
        if (circuitBreaker != null) {
            List<SmsChannel> filtered = allCandidates.stream()
                    .filter(ch -> !circuitBreaker.isOpen(ch.getName()))
                    .toList();
            // 若全部渠道均处于 OPEN 熔断态，则保底使用全量候选渠道按权重选路
            if (!filtered.isEmpty()) {
                healthyCandidates = filtered;
            } else {
                log.warn("[SmsChannelLoadBalancer] 所有候选短信渠道均处于短时熔断开路态 (OPEN)，触发保底平滑加权选路");
            }
        }

        if (healthyCandidates.size() == 1) {
            return healthyCandidates.get(0);
        }

        // 2. 执行平滑加权轮询 (SWRR) 算法
        int totalWeight = 0;
        SmsChannel selectedChannel = null;
        int maxCurrentWeight = Integer.MIN_VALUE;

        for (SmsChannel channel : healthyCandidates) {
            int weight = Math.max(1, channel.getWeight());
            totalWeight += weight;

            AtomicInteger current = currentWeights.computeIfAbsent(channel.getName(), k -> new AtomicInteger(0));
            int updatedWeight = current.addAndGet(weight);

            if (selectedChannel == null || updatedWeight > maxCurrentWeight) {
                maxCurrentWeight = updatedWeight;
                selectedChannel = channel;
            }
        }

        if (selectedChannel != null) {
            AtomicInteger selectedWeight = currentWeights.get(selectedChannel.getName());
            if (selectedWeight != null) {
                selectedWeight.addAndGet(-totalWeight);
            }
        }

        return selectedChannel;
    }

    /**
     * 重置所有渠道的动态当前权重
     */
    public synchronized void reset() {
        currentWeights.clear();
        log.info("[SmsChannelLoadBalancer] 负载均衡器动态当前权重已全部重置");
    }

    /**
     * 获取指定渠道当前的动态权重值
     *
     * @param channelName 渠道名称
     * @return 当前动态权重值
     */
    public int getCurrentWeight(String channelName) {
        BusinessAssert.hasText(channelName, "渠道名称不能为空");
        AtomicInteger current = currentWeights.get(channelName);
        return current != null ? current.get() : 0;
    }
}
