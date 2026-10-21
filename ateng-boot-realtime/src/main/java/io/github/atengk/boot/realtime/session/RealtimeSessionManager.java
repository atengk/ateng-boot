package io.github.atengk.boot.realtime.session;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.enums.EvictionPolicy;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时通信统一受管会话管理器
 * <p>
 * 基于无锁高并发 ConcurrentHashMap 统一纳管 WebSocket 与 SSE 双通道会话，
 * 提供基于 SessionKey、userId、channelType 与 tenantId 的多维极速索引，
 * 支持单用户最大连接数配额控制与先进先出（FIFO）淘汰或拒绝策略。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeSessionManager {

    private final RealtimeProperties properties;
    private final List<RealtimeSessionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 全量活跃会话主索引: SessionKey -> RealtimeSession
     */
    private final ConcurrentHashMap<SessionKey, RealtimeSession> sessions = new ConcurrentHashMap<>();

    /**
     * 用户会话多维二级索引: userId -> Set<SessionKey>
     */
    private final ConcurrentHashMap<String, Set<SessionKey>> userSessions = new ConcurrentHashMap<>();

    /**
     * 通道协议二级索引: ChannelType -> Set<SessionKey>
     */
    private final ConcurrentHashMap<ChannelType, Set<SessionKey>> channelSessions = new ConcurrentHashMap<>();

    /**
     * 租户二级索引: tenantId -> Set<SessionKey>
     */
    private final ConcurrentHashMap<Long, Set<SessionKey>> tenantSessions = new ConcurrentHashMap<>();

    public RealtimeSessionManager() {
        this(null, null);
    }

    public RealtimeSessionManager(RealtimeProperties properties) {
        this(properties, null);
    }

    public RealtimeSessionManager(RealtimeProperties properties,
                                  ObjectProvider<RealtimeSessionListener> sessionListeners) {
        this.properties = properties;
        if (sessionListeners != null) {
            sessionListeners.orderedStream().forEach(this.listeners::add);
        }
    }

    /**
     * 编程式注册会话生命周期监听器
     *
     * @param listener 监听器
     */
    public void addListener(RealtimeSessionListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    /**
     * 注册并托管新的统一实时会话
     *
     * @param session 受管会话
     * @return 是否成功注册（若触发 REJECT 配额超限则返回 false）
     */
    public boolean registerSession(RealtimeSession session) {
        if (session == null) {
            return false;
        }
        SessionKey sessionKey = session.getSessionKey();
        String userId = session.getUserId();

        // 1. 用户连接数配额检查与超限治理
        int maxPerUser = (properties != null && properties.getSession() != null)
                ? properties.getSession().getMaxConnectionsPerUser()
                : 10;
        if (maxPerUser > 0) {
            Set<SessionKey> existingKeys = userSessions.get(userId);
            if (existingKeys != null && existingKeys.size() >= maxPerUser && !existingKeys.contains(sessionKey)) {
                EvictionPolicy evictionPolicy = (properties != null && properties.getSession() != null)
                        ? properties.getSession().getEvictionPolicy()
                        : EvictionPolicy.FIFO;
                if (evictionPolicy == EvictionPolicy.REJECT) {
                    log.warn("[Realtime] 用户 [{}] 会话数已达上限 [{}], 策略为 REJECT, 拒绝新会话: {}",
                            userId, maxPerUser, sessionKey);
                    session.close(RealtimeCloseStatus.MAX_CONNECTIONS_EXCEEDED);
                    return false;
                } else {
                    // FIFO: 找到活跃时间最久远的会话并淘汰
                    SessionKey oldestKey = null;
                    long oldestTime = Long.MAX_VALUE;
                    for (SessionKey k : existingKeys) {
                        RealtimeSession s = sessions.get(k);
                        if (s != null && s.getLastActiveTime() < oldestTime) {
                            oldestTime = s.getLastActiveTime();
                            oldestKey = k;
                        }
                    }
                    if (oldestKey != null) {
                        log.info("[Realtime] 用户 [{}] 会话数已达上限 [{}], FIFO 淘汰最旧会话: {}",
                                userId, maxPerUser, oldestKey);
                        removeAndClose(oldestKey, RealtimeCloseStatus.MAX_CONNECTIONS_EXCEEDED);
                    }
                }
            }
        }

        // 2. 存入主索引（若有同 Key 存量旧连接，执行顶替关闭）
        RealtimeSession oldSession = sessions.put(sessionKey, session);
        if (oldSession != null && oldSession != session) {
            log.debug("[Realtime] 顶替相同 SessionKey 的旧会话: {}", sessionKey);
            oldSession.close(RealtimeCloseStatus.NORMAL);
        }

        // 3. 维护二级索引
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionKey);
        channelSessions.computeIfAbsent(session.getChannelType(), k -> ConcurrentHashMap.newKeySet()).add(sessionKey);
        if (session.getTenantId() != null) {
            tenantSessions.computeIfAbsent(session.getTenantId(), k -> ConcurrentHashMap.newKeySet()).add(sessionKey);
        }

        // 4. 触发注册监听回调
        notifySessionRegistered(session);
        log.debug("[Realtime] 会话注册成功: sessionKey={}, channelType={}", sessionKey, session.getChannelType());
        return true;
    }

    /**
     * 注销并移除受管会话（不主动调用底层 close）
     *
     * @param sessionKey 会话坐标
     * @return 移除的原会话实例（若不存在则返回 null）
     */
    public RealtimeSession removeSession(SessionKey sessionKey) {
        return removeSession(sessionKey, null);
    }

    /**
     * 注销并移除受管会话（携带关闭状态原因）
     *
     * @param sessionKey 会话坐标
     * @param status     关闭状态原因
     * @return 移除的原会话实例
     */
    public RealtimeSession removeSession(SessionKey sessionKey, CloseStatus status) {
        if (sessionKey == null) {
            return null;
        }
        RealtimeSession removed = sessions.remove(sessionKey);
        if (removed != null) {
            // 清理用户索引
            Set<SessionKey> userKeys = userSessions.get(removed.getUserId());
            if (userKeys != null) {
                userKeys.remove(sessionKey);
                if (userKeys.isEmpty()) {
                    userSessions.remove(removed.getUserId(), Collections.emptySet());
                }
            }
            // 清理通道索引
            Set<SessionKey> channelKeys = channelSessions.get(removed.getChannelType());
            if (channelKeys != null) {
                channelKeys.remove(sessionKey);
                if (channelKeys.isEmpty()) {
                    channelSessions.remove(removed.getChannelType(), Collections.emptySet());
                }
            }
            // 清理租户索引
            if (removed.getTenantId() != null) {
                Set<SessionKey> tenantKeys = tenantSessions.get(removed.getTenantId());
                if (tenantKeys != null) {
                    tenantKeys.remove(sessionKey);
                    if (tenantKeys.isEmpty()) {
                        tenantSessions.remove(removed.getTenantId(), Collections.emptySet());
                    }
                }
            }
            // 触发注销监听回调
            notifySessionClosed(removed, status);
            log.debug("[Realtime] 会话注销成功: sessionKey={}", sessionKey);
        }
        return removed;
    }

    /**
     * 注销并强制以指定状态码关闭会话
     *
     * @param sessionKey 会话坐标
     * @param status     关闭状态码
     * @return 关闭的原会话实例
     */
    public RealtimeSession removeAndClose(SessionKey sessionKey, CloseStatus status) {
        RealtimeSession session = removeSession(sessionKey, status);
        if (session != null) {
            session.close(status != null ? status : RealtimeCloseStatus.NORMAL);
        }
        return session;
    }

    /**
     * 根据 SessionKey 精确查询会话
     *
     * @param sessionKey 会话坐标
     * @return 会话实体（若不存在或已关闭返回 null）
     */
    public RealtimeSession getSession(SessionKey sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        RealtimeSession session = sessions.get(sessionKey);
        return (session != null && session.isOpen()) ? session : null;
    }

    /**
     * 根据用户标识查询其名下所有活跃会话（多端多设备）
     *
     * @param userId 用户标识
     * @return 会话不可变列表（保底返回空列表，绝不返回 null）
     */
    public List<RealtimeSession> getSessionsByUser(Object userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Set<SessionKey> keys = userSessions.get(String.valueOf(userId));
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RealtimeSession> result = new ArrayList<>(keys.size());
        for (SessionKey k : keys) {
            RealtimeSession s = sessions.get(k);
            if (s != null && s.isOpen()) {
                result.add(s);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 根据通道协议类型查询全量活跃会话
     *
     * @param channelType 通道协议类型
     * @return 会话不可变列表
     */
    public List<RealtimeSession> getSessionsByChannel(ChannelType channelType) {
        if (channelType == null) {
            return Collections.emptyList();
        }
        Set<SessionKey> keys = channelSessions.get(channelType);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RealtimeSession> result = new ArrayList<>(keys.size());
        for (SessionKey k : keys) {
            RealtimeSession s = sessions.get(k);
            if (s != null && s.isOpen()) {
                result.add(s);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 根据多租户标识查询归属租户的全量活跃会话
     *
     * @param tenantId 租户标识
     * @return 会话不可变列表
     */
    public List<RealtimeSession> getSessionsByTenant(Long tenantId) {
        if (tenantId == null) {
            return Collections.emptyList();
        }
        Set<SessionKey> keys = tenantSessions.get(tenantId);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RealtimeSession> result = new ArrayList<>(keys.size());
        for (SessionKey k : keys) {
            RealtimeSession s = sessions.get(k);
            if (s != null && s.isOpen()) {
                result.add(s);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取全量活跃受管会话快照
     *
     * @return 全量会话不可变列表
     */
    public List<RealtimeSession> getAllSessions() {
        if (sessions.isEmpty()) {
            return Collections.emptyList();
        }
        List<RealtimeSession> result = new ArrayList<>(sessions.size());
        for (RealtimeSession s : sessions.values()) {
            if (s != null && s.isOpen()) {
                result.add(s);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取当前节点在线总连接数
     */
    public int getOnlineCount() {
        return sessions.size();
    }

    /**
     * 获取当前节点在线总独立用户数
     */
    public int getOnlineUserCount() {
        return userSessions.size();
    }

    /**
     * 获取指定协议通道的在线连接数
     *
     * @param channelType 通道类型
     */
    public int getOnlineCountByChannel(ChannelType channelType) {
        if (channelType == null) {
            return 0;
        }
        Set<SessionKey> keys = channelSessions.get(channelType);
        return keys != null ? keys.size() : 0;
    }

    /**
     * 判断当前会话是否存在且活跃
     *
     * @param sessionKey 会话坐标
     */
    public boolean containsSession(SessionKey sessionKey) {
        if (sessionKey == null) {
            return false;
        }
        RealtimeSession s = sessions.get(sessionKey);
        return s != null && s.isOpen();
    }

    /**
     * 清空并关闭当前节点全量活跃会话（常用于优雅停机或系统重置）
     */
    public void clear() {
        Set<SessionKey> allKeys = new HashSet<>(sessions.keySet());
        for (SessionKey key : allKeys) {
            removeAndClose(key, RealtimeCloseStatus.SERVICE_RESTART);
        }
        sessions.clear();
        userSessions.clear();
        channelSessions.clear();
        tenantSessions.clear();
    }

    private void notifySessionRegistered(RealtimeSession session) {
        for (RealtimeSessionListener listener : listeners) {
            try {
                listener.onSessionRegistered(session);
            } catch (Throwable t) {
                log.warn("[Realtime] 会话注册监听器执行异常: listener={}, error={}", listener.getClass().getName(), t.getMessage());
            }
        }
    }

    private void notifySessionClosed(RealtimeSession session, CloseStatus status) {
        for (RealtimeSessionListener listener : listeners) {
            try {
                listener.onSessionClosed(session, status);
            } catch (Throwable t) {
                log.warn("[Realtime] 会话关闭监听器执行异常: listener={}, error={}", listener.getClass().getName(), t.getMessage());
            }
        }
    }
}
