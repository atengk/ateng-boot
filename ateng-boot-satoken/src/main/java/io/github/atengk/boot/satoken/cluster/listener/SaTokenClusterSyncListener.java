package io.github.atengk.boot.satoken.cluster.listener;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import io.github.atengk.boot.satoken.cluster.enums.SaTokenClusterEventType;
import io.github.atengk.boot.satoken.cluster.model.SaTokenClusterMessage;
import io.github.atengk.boot.satoken.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Sa-Token 集群同步监听器
 * 1. 监听 Spring 原生安全生命周期事件，自动驱动向全集群广播事件
 * 2. 消费集群广播消息，执行自重入防卫（Self-Consumption Guard）并对异地消息精准执行本地缓存失效
 * 3. 纯 Spring / POJO 组件，零依赖外部 Redis 类型，确保单机无 Redis 环境下的绝对平滑降级
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenClusterSyncListener {

    private final SaTokenClusterBroadcaster broadcaster;
    private final PermissionCacheService cacheService;

    // ==================== 1. Spring 安全事件监听与广播触发 ====================

    @EventListener
    public void onLogout(SaLogoutEvent event) {
        String loginId = event.getLoginId() != null ? String.valueOf(event.getLoginId()) : null;
        broadcaster.broadcast(SaTokenClusterEventType.LOGOUT, loginId);
    }

    @EventListener
    public void onKickout(SaKickoutEvent event) {
        String loginId = event.getLoginId() != null ? String.valueOf(event.getLoginId()) : null;
        broadcaster.broadcast(SaTokenClusterEventType.KICKOUT, loginId);
    }

    @EventListener
    public void onReplaced(SaReplacedEvent event) {
        String loginId = event.getLoginId() != null ? String.valueOf(event.getLoginId()) : null;
        broadcaster.broadcast(SaTokenClusterEventType.REPLACED, loginId);
    }

    @EventListener
    public void onDisable(SaDisableEvent event) {
        String loginId = event.getLoginId() != null ? String.valueOf(event.getLoginId()) : null;
        broadcaster.broadcast(SaTokenClusterEventType.DISABLE, loginId);
    }

    @EventListener
    public void onUntieDisable(SaUntieDisableEvent event) {
        String loginId = event.getLoginId() != null ? String.valueOf(event.getLoginId()) : null;
        broadcaster.broadcast(SaTokenClusterEventType.UNTIE_DISABLE, loginId);
    }

    // ==================== 2. 集群广播消息消费与本地精准失效 ====================

    /**
     * 接收并处理集群广播 JSON 载荷
     *
     * @param payload 消息 JSON 字符串
     */
    public void onClusterMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            SaTokenClusterMessage clusterMessage = JsonUtil.parseObject(payload, SaTokenClusterMessage.class);
            if (clusterMessage == null || clusterMessage.getEventType() == null) {
                return;
            }

            // 1. 自重入防卫过滤（Self-Consumption Guard）
            if (broadcaster != null && broadcaster.getInstanceId() != null
                    && broadcaster.getInstanceId().equals(clusterMessage.getInstanceId())) {
                log.trace("忽略处理本机节点广播的集群同步消息（防自重入）: instanceId={}, eventType={}",
                        clusterMessage.getInstanceId(), clusterMessage.getEventType());
                return;
            }

            // 2. 异地消息精准分发失效
            log.info("收到异地节点集群广播同步消息: sourceInstanceId={}, eventType={}, targetKey={}",
                    clusterMessage.getInstanceId(), clusterMessage.getEventType(), clusterMessage.getTargetKey());

            processRemoteMessage(clusterMessage);
        } catch (Throwable ex) {
            log.warn("处理 Sa-Token 集群广播同步消息异常: error={}", ex.getMessage(), ex);
        }
    }

    /**
     * 处理异地节点集群广播消息的具体失效逻辑
     *
     * @param clusterMessage 广播消息
     */
    public void processRemoteMessage(SaTokenClusterMessage clusterMessage) {
        if (clusterMessage == null || clusterMessage.getEventType() == null) {
            return;
        }
        String targetKey = clusterMessage.getTargetKey();
        switch (clusterMessage.getEventType()) {
            case USER_PERMISSION_REFRESH, KICKOUT, LOGOUT, REPLACED, DISABLE, UNTIE_DISABLE -> {
                if (targetKey != null && !targetKey.isBlank()) {
                    cacheService.clearLocalUserCache(targetKey);
                }
            }
            case ROLE_PERMISSION_REFRESH -> {
                if (targetKey != null && !targetKey.isBlank()) {
                    cacheService.clearLocalRoleCache(targetKey);
                }
            }
            case ALL_CACHE_CLEAR -> cacheService.clearLocalAllCache();
            default -> log.debug("收到未特殊处理的异地集群消息: eventType={}", clusterMessage.getEventType());
        }
    }
}
