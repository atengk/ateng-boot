package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.properties.TcpClusterProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * 基于 Redis Pub/Sub 的两级自适应 TCP 集群推送桥接实现
 * <p>
 * 遵循“本地直发优先 + 跨节点去中心化广播”两级拓扑，支持自动防回环过滤。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class RedisPubSubTcpClusterBridge implements TcpClusterBridge, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubTcpClusterBridge.class);

    private final String nodeId = IdUtil.fastSimpleUUID();
    private final TcpSessionManager sessionManager;
    private final StringRedisTemplate redisTemplate;
    private final String topic;

    public RedisPubSubTcpClusterBridge(TcpSessionManager sessionManager,
                                       StringRedisTemplate redisTemplate,
                                       TcpClusterProperties clusterProperties) {
        this.sessionManager = sessionManager;
        this.redisTemplate = redisTemplate;
        this.topic = (clusterProperties != null && clusterProperties.getRedis() != null)
                ? clusterProperties.getRedis().getTopic()
                : "ateng:tcp:cluster:broadcast";
        log.info("初始化 TCP 集群 Redis 广播推送桥接: nodeId={}, topic={}", nodeId, topic);
    }

    @Override
    public boolean sendToCluster(String deviceId, Object payload) {
        Assert.hasText(deviceId, "deviceId 不能为空");
        Assert.notNull(payload, "payload 不能为空");

        // 1. 第一级：本地优先直发（零网络开销与最低延迟）
        TcpSession localSession = sessionManager.getSessionByDeviceId(deviceId);
        if (localSession != null && localSession.isActive()) {
            localSession.send(payload);
            log.debug("[TCP-CLUSTER] 设备 [{}] 在本机直连，完成本地直发", deviceId);
            return true;
        }

        // 2. 第二级：本地未命中，投递至 Redis Pub/Sub 去中心化广播
        try {
            TcpClusterBroadcastMessage broadcastMessage = TcpClusterBroadcastMessage.of(deviceId, nodeId, payload);
            String messageJson = JsonUtil.toJsonString(broadcastMessage);
            redisTemplate.convertAndSend(topic, messageJson);
            log.debug("[TCP-CLUSTER] 设备 [{}] 本地未命中，已向 Redis 广播通道 [{}] 派发指令", deviceId, topic);
            return true;
        } catch (Exception ex) {
            log.error("[TCP-CLUSTER] 向 Redis 广播通道投递设备 [{}] 推送消息异常", deviceId, ex);
            return false;
        }
    }

    @Override
    public boolean broadcastToCluster(Object payload) {
        Assert.notNull(payload, "payload 不能为空");

        // 1. 本地直发存量连接
        Collection<TcpSession> sessions = sessionManager.getAllSessions();
        for (TcpSession session : sessions) {
            if (session.isActive()) {
                session.send(payload);
            }
        }

        // 2. 广播至 Redis 让集群其他 Pod 下发
        try {
            TcpClusterBroadcastMessage broadcastMessage = TcpClusterBroadcastMessage.of(null, nodeId, payload);
            String messageJson = JsonUtil.toJsonString(broadcastMessage);
            redisTemplate.convertAndSend(topic, messageJson);
            return true;
        } catch (Exception ex) {
            log.error("[TCP-CLUSTER] 向 Redis 广播全量消息异常", ex);
            return false;
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            byte[] body = message.getBody();
            if (body == null || body.length == 0) {
                return;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            TcpClusterBroadcastMessage broadcastMessage = JsonUtil.parseObject(json, TcpClusterBroadcastMessage.class);
            if (broadcastMessage == null) {
                return;
            }

            // 1. 防回环守卫：丢弃本节点自身发出的广播
            if (nodeId.equals(broadcastMessage.getSourceNodeId())) {
                return;
            }

            String targetDeviceId = broadcastMessage.getDeviceId();
            Object resolvedPayload = broadcastMessage.resolvePayload();

            if (targetDeviceId != null) {
                // 点播定向下发
                TcpSession localSession = sessionManager.getSessionByDeviceId(targetDeviceId);
                if (localSession != null && localSession.isActive()) {
                    localSession.send(resolvedPayload);
                    log.debug("[TCP-CLUSTER] 本节点收到跨节点广播并成功命中设备 [{}]，完成物理下发", targetDeviceId);
                }
            } else {
                // 集群全量广播
                Collection<TcpSession> sessions = sessionManager.getAllSessions();
                for (TcpSession session : sessions) {
                    if (session.isActive()) {
                        session.send(resolvedPayload);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("[TCP-CLUSTER] 处理 Redis 跨节点广播消息异常", ex);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getTopic() {
        return topic;
    }
}
