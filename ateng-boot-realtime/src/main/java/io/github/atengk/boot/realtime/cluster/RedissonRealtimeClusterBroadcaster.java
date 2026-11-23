package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.web.socket.CloseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
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
public class RedissonRealtimeClusterBroadcaster implements RealtimeClusterBroadcaster, RealtimeSessionListener, InitializingBean, DisposableBean {

    private static final int PRESENCE_LOCK_STRIPES = 64;

    private final RedissonClient redissonClient;
    private final RealtimeProperties properties;
    @Getter
    private final String nodeId;
    private final List<Consumer<RealtimeClusterPacket>> consumers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Set<String>> localUserSessions = new ConcurrentHashMap<>();
    private final ReentrantLock[] userSyncLocks = createLockStripes();

    private RTopic topic;
    private Integer listenerId;

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[PRESENCE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

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
        if (packet == null || topic == null) {
            return;
        }
        if (packet.getSourceNodeId() == null || packet.getSourceNodeId().isBlank()) {
            packet.setSourceNodeId(this.nodeId);
        }
        try {
            String json = JsonUtil.toJsonString(packet);
            RFuture<Long> future = topic.publishAsync(json);
            if (future != null) {
                future.whenComplete((count, ex) -> {
                    if (ex != null) {
                        log.warn("[Realtime-Cluster] 广播消息异步发布失败: type={}, target={}, error={}",
                                packet.getBroadcastType(), packet.getTargetKey(), ex.getMessage());
                    } else if (log.isTraceEnabled()) {
                        log.trace("[Realtime-Cluster] 成功向集群异步发布广播: type={}, target={}, subscribers={}",
                                packet.getBroadcastType(), packet.getTargetKey(), count);
                    }
                });
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
    public boolean isUserOnline(Object userId) {
        if (userId == null) {
            return false;
        }
        String userKey = String.valueOf(userId);
        try {
            RMap<String, String> userNodeMap = redissonClient.getMap(getUserPresenceHashKey(userKey), StringCodec.INSTANCE);
            Map<String, String> nodeEntries = userNodeMap != null ? userNodeMap.readAllMap() : null;
            if (nodeEntries == null || nodeEntries.isEmpty()) {
                return false;
            }
            long now = System.currentTimeMillis();
            long expireMillis = ClusterNodeHeartbeatSupport.resolveExpirationMillis(properties);
            Map<String, String> clusterNodesSnapshot = null;
            boolean clusterNodesLoaded = false;

            boolean online = false;
            List<String> staleNodeIds = new ArrayList<>();
            for (Map.Entry<String, String> entry : nodeEntries.entrySet()) {
                String remoteNodeId = entry.getKey();
                int count = parsePositiveCount(entry.getValue());
                if (remoteNodeId == null || count <= 0) {
                    if (remoteNodeId != null) {
                        staleNodeIds.add(remoteNodeId);
                    }
                    continue;
                }
                if (Objects.equals(this.nodeId, remoteNodeId)) {
                    online = true;
                    continue;
                }
                if (!clusterNodesLoaded) {
                    RMap<String, String> clusterNodesMap = redissonClient.getMap(
                            ClusterNodeHeartbeatSupport.resolveNodesHashKey(properties), StringCodec.INSTANCE);
                    clusterNodesSnapshot = clusterNodesMap != null ? clusterNodesMap.readAllMap() : null;
                    clusterNodesLoaded = true;
                }
                if (isClusterNodeAlive(clusterNodesSnapshot, remoteNodeId, now, expireMillis)) {
                    online = true;
                } else {
                    staleNodeIds.add(remoteNodeId);
                }
            }
            cleanupStaleUserNodes(userNodeMap, staleNodeIds, !online && staleNodeIds.size() == nodeEntries.size());
            return online;
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 查询集群用户在线状态异常: userId={}, error={}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public void onSessionRegistered(RealtimeSession session) {
        if (session == null || session.getUserId() == null || session.getSessionKey() == null) {
            return;
        }
        String userKey = String.valueOf(session.getUserId());
        String sessionKey = session.getSessionKey().format();
        ReentrantLock lock = lockForUser(userKey);
        lock.lock();
        try {
            Set<String> sessions = localUserSessions.computeIfAbsent(userKey, k -> ConcurrentHashMap.newKeySet());
            sessions.add(sessionKey);
            syncUserPresence(userKey, sessions.size());
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 同步集群用户上线状态异常: userId={}, error={}", userKey, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void onSessionClosed(RealtimeSession session, CloseStatus closeStatus) {
        if (session == null || session.getUserId() == null || session.getSessionKey() == null) {
            return;
        }
        String userKey = String.valueOf(session.getUserId());
        String sessionKey = session.getSessionKey().format();
        ReentrantLock lock = lockForUser(userKey);
        lock.lock();
        try {
            Set<String> sessions = localUserSessions.get(userKey);
            int remaining = 0;
            if (sessions != null) {
                sessions.remove(sessionKey);
                remaining = sessions.size();
                if (remaining == 0) {
                    localUserSessions.remove(userKey, sessions);
                }
            }
            syncUserPresence(userKey, remaining);
        } catch (Exception e) {
            log.warn("[Realtime-Cluster] 同步集群用户下线状态异常: userId={}, error={}", userKey, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void syncUserPresence(String userKey, int sessionCount) {
        RMap<String, String> map = redissonClient.getMap(getUserPresenceHashKey(userKey), StringCodec.INSTANCE);
        if (map != null) {
            if (sessionCount > 0) {
                map.put(this.nodeId, String.valueOf(sessionCount));
            } else {
                map.remove(this.nodeId);
            }
        }
        RSet<String> nodeUsersSet = redissonClient.getSet(
                ClusterNodeHeartbeatSupport.resolveNodeUsersSetKey(properties, this.nodeId), StringCodec.INSTANCE);
        if (nodeUsersSet != null) {
            if (sessionCount > 0) {
                nodeUsersSet.add(userKey);
            } else {
                nodeUsersSet.remove(userKey);
            }
        }
    }

    private boolean isClusterNodeAlive(Map<String, String> clusterNodesSnapshot,
                                       String targetNodeId,
                                       long nowMillis,
                                       long expireMillis) {
        if (clusterNodesSnapshot == null || targetNodeId == null) {
            return false;
        }
        String rawHeartbeat = clusterNodesSnapshot.get(targetNodeId);
        if (rawHeartbeat == null || rawHeartbeat.isBlank()) {
            return false;
        }
        ClusterNodeHeartbeatSupport.HeartbeatEntry heartbeat =
                ClusterNodeHeartbeatSupport.parseHeartbeatValue(rawHeartbeat);
        return heartbeat != null && !ClusterNodeHeartbeatSupport.isExpired(heartbeat, nowMillis, expireMillis);
    }

    private void cleanupStaleUserNodes(RMap<String, String> userNodeMap,
                                       List<String> staleNodeIds,
                                       boolean removeEmptyHash) {
        if (userNodeMap == null || staleNodeIds == null || staleNodeIds.isEmpty()) {
            return;
        }
        for (String staleNodeId : staleNodeIds) {
            try {
                userNodeMap.remove(staleNodeId);
            } catch (Exception ex) {
                log.debug("[Realtime-Cluster] 清理死节点用户在线残留异常: staleNodeId={}, error={}",
                        staleNodeId, ex.getMessage());
            }
        }
        if (removeEmptyHash) {
            try {
                userNodeMap.delete();
            } catch (Exception ex) {
                log.debug("[Realtime-Cluster] 删除已清空的用户节点状态 Hash 失败: error={}", ex.getMessage());
            }
        }
    }

    private int parsePositiveCount(String rawCount) {
        ClusterNodeHeartbeatSupport.HeartbeatEntry entry = ClusterNodeHeartbeatSupport.parseHeartbeatValue(rawCount);
        return entry != null ? entry.count() : 0;
    }

    private ReentrantLock lockForUser(String userKey) {
        int stripe = (userKey.hashCode() & Integer.MAX_VALUE) % userSyncLocks.length;
        return userSyncLocks[stripe];
    }

    private String getUserPresenceHashKey(String userId) {
        return ClusterNodeHeartbeatSupport.resolveUserPresenceHashKey(properties, userId);
    }

    @Override
    public void addConsumer(Consumer<RealtimeClusterPacket> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        consumers.add(consumer);
    }

    @Override
    public void removeConsumer(Consumer<RealtimeClusterPacket> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        consumers.remove(consumer);
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
        for (String userKey : new ArrayList<>(localUserSessions.keySet())) {
            ReentrantLock lock = lockForUser(userKey);
            lock.lock();
            try {
                localUserSessions.remove(userKey);
                syncUserPresence(userKey, 0);
            } catch (Exception ex) {
                log.warn("[Realtime-Cluster] 停机注销本节点用户在线状态异常: userId={}, error={}", userKey, ex.getMessage());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        try {
            RSet<String> nodeUsersSet = redissonClient.getSet(
                    ClusterNodeHeartbeatSupport.resolveNodeUsersSetKey(properties, this.nodeId), StringCodec.INSTANCE);
            if (nodeUsersSet != null) {
                nodeUsersSet.delete();
            }
        } catch (Exception ex) {
            log.debug("[Realtime-Cluster] 停机删除节点用户反向索引失败: nodeId={}, error={}", this.nodeId, ex.getMessage());
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
