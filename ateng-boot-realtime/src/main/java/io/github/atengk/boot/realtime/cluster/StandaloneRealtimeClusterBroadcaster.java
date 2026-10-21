package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 实时通信单机保底广播器实现
 * <p>
 * 当未引入 Redis 客户端或配置强制单机模式时激活，广播操作直接作为 No-Op 降级处理，
 * 确保单机架构零外部依赖平滑运行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class StandaloneRealtimeClusterBroadcaster implements RealtimeClusterBroadcaster {

    private final String nodeId;

    public StandaloneRealtimeClusterBroadcaster() {
        this.nodeId = "standalone-" + IdUtil.fastSimpleUUID();
        log.info("[Realtime] 实时底座已激活单机保底模式 (Standalone Mode), nodeId={}", this.nodeId);
    }

    @Override
    public void broadcast(RealtimeClusterPacket packet) {
        if (log.isTraceEnabled()) {
            log.trace("[Realtime-Cluster] 单机模式忽略远程广播: type={}, target={}",
                    packet != null ? packet.getBroadcastType() : null,
                    packet != null ? packet.getTargetKey() : null);
        }
    }

    @Override
    public String getNodeId() {
        return this.nodeId;
    }

    @Override
    public boolean isClusterEnabled() {
        return false;
    }

    @Override
    public void addConsumer(Consumer<RealtimeClusterPacket> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        // 单机模式无需订阅远程消息
    }
}
