package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 Redisson 的分布式集群广播器实现
 * <p>
 * 依托 Redisson {@link RTopic} 实现 Redis Pub/Sub 跨节点消息广播，
 * 内置 {@code sourceNodeId} 防反射自发自收过滤，支持动态注册消费回调。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RedissonRealtimeClusterBroadcaster implements RealtimeClusterBroadcaster, InitializingBean, DisposableBean {

    private final RedissonClient redissonClient;
    private final RealtimeProperties properties;
    @Getter
    private final String nodeId;
    private final List<Consumer<RealtimeClusterPacket>> consumers = new CopyOnWriteArrayList<>();

    private RTopic topic;
    private Integer listenerId;

    public RedissonRealtimeClusterBroadcaster(RedissonClient redissonClient, RealtimeProperties properties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.nodeId = IdUtil.fastSimpleUUID();
    }

    @Override
    public void afterPropertiesSet() {
        String topicName = properties.getCluster().getTopic();
        this.topic = redissonClient.getTopic(topicName);
        this.listenerId = topic.addListener(String.class, (channel, msg) -> handleIncomingMessage(msg));
        log.info("[Realtime] Redisson 集群广播器已激活 (Cluster Mode), nodeId={}, topic={}", this.nodeId, topicName);
    }

    @Override
    public void broadcast(RealtimeClusterPacket packet) {
        if (packet == null) {
            return;
        }
        if (packet.getSourceNodeId() == null || packet.getSourceNodeId().isBlank()) {
            packet.setSourceNodeId(this.nodeId);
        }
        try {
            String json = JsonUtil.toJsonString(packet);
            topic.publish(json);
            if (log.isTraceEnabled()) {
                log.trace("[Realtime-Cluster] 成功向集群发布广播: type={}, target={}",
                        packet.getBroadcastType(), packet.getTargetKey());
            }
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 广播消息发布失败: type={}, target={}, error={}",
                    packet.getBroadcastType(), packet.getTargetKey(), e.getMessage());
        }
    }

    @Override
    public boolean isClusterEnabled() {
        return true;
    }

    @Override
    public void addConsumer(Consumer<RealtimeClusterPacket> consumer) {
        if (consumer != null) {
            consumers.add(consumer);
        }
    }

    @Override
    public void destroy() {
        if (topic != null && listenerId != null) {
            try {
                topic.removeListener(listenerId);
                log.info("[Realtime] Redisson 集群广播器监听已注销: nodeId={}", this.nodeId);
            } catch (Exception e) {
                log.warn("[Realtime] 注销 Redisson 监听器异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 接收并处理远端广播消息，内置防反射丢弃过滤
     */
    private void handleIncomingMessage(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            RealtimeClusterPacket packet = JsonUtil.parseObject(json, RealtimeClusterPacket.class);
            if (packet == null) {
                return;
            }
            // 防反射过滤：自发自收报文快速丢弃
            if (Objects.equals(packet.getSourceNodeId(), this.nodeId)) {
                if (log.isTraceEnabled()) {
                    log.trace("[Realtime-Cluster] 忽略源自本节点的广播反射包: type={}, target={}",
                            packet.getBroadcastType(), packet.getTargetKey());
                }
                return;
            }
            // 分发给本地已注册的所有消费者
            for (Consumer<RealtimeClusterPacket> consumer : consumers) {
                try {
                    consumer.accept(packet);
                } catch (Exception ex) {
                    log.error("[Realtime-Cluster] 消费远端集群广播报文异常: type={}, target={}, error={}",
                            packet.getBroadcastType(), packet.getTargetKey(), ex.getMessage(), ex);
                }
            }
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 解析远端集群广播报文失败: error={}", e.getMessage());
        }
    }
}
