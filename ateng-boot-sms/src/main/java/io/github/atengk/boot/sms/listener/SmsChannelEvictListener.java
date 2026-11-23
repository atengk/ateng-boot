package io.github.atengk.boot.sms.listener;

import io.github.atengk.boot.sms.event.SmsChannelEvictEvent;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.loadbalancer.SmsChannelLoadBalancer;
import io.github.atengk.boot.sms.registry.SmsChannelEvictBroadcaster;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/**
 * 动态短信渠道热驱逐领域事件监听器
 * <p>
 * 响应 {@link SmsChannelEvictEvent}，同步驱逐 {@link SmsChannelRegistry} 动态缓存并联动清理
 * {@link SmsChannelLoadBalancer} 权重残留与 {@link SmsChannelCircuitBreaker} 熔断态；
 * 若已启用集群广播桥接且事件源自本节点，则自动同步至 Redis Pub/Sub 频道。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class SmsChannelEvictListener {

    private final SmsChannelRegistry channelRegistry;
    private final SmsChannelLoadBalancer loadBalancer;
    private final SmsChannelCircuitBreaker circuitBreaker;
    private final SmsChannelEvictBroadcaster clusterBroadcaster;

    public SmsChannelEvictListener(SmsChannelRegistry channelRegistry,
                                   SmsChannelLoadBalancer loadBalancer,
                                   SmsChannelCircuitBreaker circuitBreaker,
                                   SmsChannelEvictBroadcaster clusterBroadcaster) {
        this.channelRegistry = Objects.requireNonNull(channelRegistry, "channelRegistry must not be null");
        this.loadBalancer = loadBalancer;
        this.circuitBreaker = circuitBreaker;
        this.clusterBroadcaster = clusterBroadcaster;
    }

    public SmsChannelEvictListener(SmsChannelRegistry channelRegistry,
                                   SmsChannelLoadBalancer loadBalancer,
                                   SmsChannelCircuitBreaker circuitBreaker) {
        this(channelRegistry, loadBalancer, circuitBreaker, null);
    }

    /**
     * 监听并执行短信渠道热驱逐与集群同步广播
     *
     * @param event 渠道驱逐领域事件
     */
    @EventListener
    public void onChannelEvict(SmsChannelEvictEvent event) {
        if (event == null) {
            return;
        }
        if (event.isClearAll()) {
            log.info("[SmsChannelEvictListener] 收到全量渠道驱逐事件，同步清空动态缓存、负载权重与熔断健康态");
            channelRegistry.evictAllDynamic();
            if (loadBalancer != null) {
                loadBalancer.reset();
            }
            if (circuitBreaker != null) {
                circuitBreaker.resetAll();
            }
        } else {
            String channelName = event.getChannelName();
            log.info("[SmsChannelEvictListener] 收到渠道 [{}] 驱逐事件，同步淘汰动态缓存并清理负载权重与熔断态", channelName);
            channelRegistry.evictDynamic(channelName);
            if (loadBalancer != null) {
                loadBalancer.reset(channelName);
            }
            if (circuitBreaker != null) {
                circuitBreaker.reset(channelName);
            }
        }

        if (clusterBroadcaster != null && !event.isFromCluster()) {
            clusterBroadcaster.publishEvict(event.getChannelName());
        }
    }
}
