package io.github.atengk.boot.realtime.template;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

/**
 * 实时通信统一消息推送与会话治理门面
 * <p>
 * 提供对等统一的高并发单机消息推送能力，屏蔽底层 WebSocket 与 SSE 协议异构性，
 * 完备覆盖点对点单播（sendToSession/sendToUser）、多端协议定向推流（sendViaWebSocket/sendViaSse）、
 * 房间分组组播（sendToGroup）、租户隔离广播（sendToTenant）、全局广播（sendToAll）、
 * 主动会话驱逐（kickoutUser/kickoutSession）及全维实时在线度量统计等工业级 API。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeTemplate {

    @Getter
    private final RealtimeSessionManager sessionManager;
    @Getter
    private final RealtimeGroupManager groupManager;
    @Getter
    private final RealtimeProperties properties;

    public RealtimeTemplate(RealtimeSessionManager sessionManager,
                            RealtimeGroupManager groupManager,
                            RealtimeProperties properties) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.groupManager = Objects.requireNonNull(groupManager, "groupManager must not be null");
        this.properties = properties;
    }

    // ==================== 点对点单播 (Point-to-Point Unicast) ====================

    /**
     * 向指定精确三维坐标的会话发送实时消息
     *
     * @param sessionKey 会话坐标
     * @param message    实时业务消息
     * @return 是否成功投递
     */
    public boolean sendToSession(SessionKey sessionKey, RealtimeMessage<?> message) {
        if (sessionKey == null || message == null) {
            return false;
        }
        RealtimeSession session = sessionManager.getSession(sessionKey);
        return doSend(session, message);
    }

    /**
     * 向指定用户的指定客户端发送实时消息（默认默认设备）
     *
     * @param userId   用户标识
     * @param clientId 客户端标识
     * @param message  实时业务消息
     * @return 是否成功投递
     */
    public boolean sendToSession(Object userId, String clientId, RealtimeMessage<?> message) {
        if (userId == null || clientId == null || message == null) {
            return false;
        }
        SessionKey sessionKey = SessionKey.of(userId, clientId);
        return sendToSession(sessionKey, message);
    }

    /**
     * 向指定用户的所有在线设备/通道会话广播下发消息
     *
     * @param userId  用户标识
     * @param message 实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToUser(Object userId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getSessionsByUser(userId), null, message);
    }

    /**
     * 向指定租户下的指定用户的所有在线设备会话下发消息
     *
     * @param userId   用户标识
     * @param tenantId 租户标识
     * @param message  实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToUser(Object userId, Long tenantId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getSessionsByUser(userId),
                s -> tenantId == null || Objects.equals(tenantId, s.getTenantId()),
                message);
    }

    // ==================== 协议定向推流 (Protocol-Directed Sending) ====================

    /**
     * 仅当指定会话为 WebSocket 协议时发送消息
     *
     * @param sessionKey 会话坐标
     * @param message    实时业务消息
     * @return 是否成功投递
     */
    public boolean sendViaWebSocket(SessionKey sessionKey, RealtimeMessage<?> message) {
        if (sessionKey == null || message == null) {
            return false;
        }
        RealtimeSession session = sessionManager.getSession(sessionKey);
        if (session == null || session.getChannelType() != ChannelType.WEBSOCKET) {
            return false;
        }
        return doSend(session, message);
    }

    /**
     * 仅当指定会话为 SSE 协议时发送消息
     *
     * @param sessionKey 会话坐标
     * @param message    实时业务消息
     * @return 是否成功投递
     */
    public boolean sendViaSse(SessionKey sessionKey, RealtimeMessage<?> message) {
        if (sessionKey == null || message == null) {
            return false;
        }
        RealtimeSession session = sessionManager.getSession(sessionKey);
        if (session == null || session.getChannelType() != ChannelType.SSE) {
            return false;
        }
        return doSend(session, message);
    }

    /**
     * 向指定用户的所有在线 WebSocket 会话下发消息
     *
     * @param userId  用户标识
     * @param message 实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToUserViaWebSocket(Object userId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getSessionsByUser(userId),
                s -> s.getChannelType() == ChannelType.WEBSOCKET,
                message);
    }

    /**
     * 向指定用户的所有在线 SSE 会话下发消息
     *
     * @param userId  用户标识
     * @param message 实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToUserViaSse(Object userId, RealtimeMessage<?> message) {
        if (userId == null || message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getSessionsByUser(userId),
                s -> s.getChannelType() == ChannelType.SSE,
                message);
    }

    // ==================== 分组协同与频道组播 (Group Multicast) ====================

    /**
     * 向指定分组/房间内的所有活跃成员会话广播消息
     *
     * @param groupId 群组标识
     * @param message 实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToGroup(String groupId, RealtimeMessage<?> message) {
        if (groupId == null || groupId.isBlank() || message == null) {
            return 0;
        }
        Set<SessionKey> members = groupManager.getGroupSessions(groupId);
        if (members.isEmpty()) {
            return 0;
        }
        int successCount = 0;
        for (SessionKey key : members) {
            RealtimeSession session = sessionManager.getSession(key);
            if (session != null && doSend(session, message)) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * 将指定会话加入分组
     *
     * @param groupId    群组标识
     * @param sessionKey 会话坐标
     * @return 是否加入成功
     */
    public boolean joinGroup(String groupId, SessionKey sessionKey) {
        return groupManager.joinGroup(groupId, sessionKey);
    }

    /**
     * 将指定会话移出分组
     *
     * @param groupId    群组标识
     * @param sessionKey 会话坐标
     * @return 是否移出成功
     */
    public boolean leaveGroup(String groupId, SessionKey sessionKey) {
        return groupManager.leaveGroup(groupId, sessionKey);
    }

    /**
     * 将指定用户当前所有的在线设备会话加入指定分组
     *
     * @param groupId 群组标识
     * @param userId  用户标识
     * @return 加入成功的会话数量
     */
    public int joinGroup(String groupId, Object userId) {
        if (groupId == null || groupId.isBlank() || userId == null) {
            return 0;
        }
        List<RealtimeSession> sessions = sessionManager.getSessionsByUser(userId);
        int count = 0;
        for (RealtimeSession s : sessions) {
            if (groupManager.joinGroup(groupId, s.getSessionKey())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 将指定用户当前所有的在线设备会话移出指定分组
     *
     * @param groupId 群组标识
     * @param userId  用户标识
     * @return 移出成功的会话数量
     */
    public int leaveGroup(String groupId, Object userId) {
        if (groupId == null || groupId.isBlank() || userId == null) {
            return 0;
        }
        List<RealtimeSession> sessions = sessionManager.getSessionsByUser(userId);
        int count = 0;
        for (RealtimeSession s : sessions) {
            if (groupManager.leaveGroup(groupId, s.getSessionKey())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取指定群组名下的所有会话坐标
     *
     * @param groupId 群组标识
     * @return 会话坐标集合（空安全防御，绝不返回 null）
     */
    public Set<SessionKey> getGroupSessions(String groupId) {
        return groupManager.getGroupSessions(groupId);
    }

    /**
     * 获取指定群组的在线成员总数
     *
     * @param groupId 群组标识
     */
    public int getGroupMemberCount(String groupId) {
        return groupManager.getGroupMemberCount(groupId);
    }

    // ==================== 租户隔离广播与全服广播 (Tenant & Global Broadcast) ====================

    /**
     * 向指定多租户下的所有活跃会话广播消息
     *
     * @param tenantId 租户标识
     * @param message  实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToTenant(Long tenantId, RealtimeMessage<?> message) {
        if (tenantId == null || message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getSessionsByTenant(tenantId), null, message);
    }

    /**
     * 向当前单机节点纳管的全量活跃会话广播消息
     *
     * @param message 实时业务消息
     * @return 成功投递的会话数量
     */
    public int sendToAll(RealtimeMessage<?> message) {
        if (message == null) {
            return 0;
        }
        return sendToSessions(sessionManager.getAllSessions(), null, message);
    }

    // ==================== 主动会话驱逐与下线 (Kickout & Eviction) ====================

    /**
     * 主动驱逐指定会话下线（携带自定义关闭状态码）
     *
     * @param sessionKey 会话坐标
     * @param status     关闭状态原因
     * @return 是否成功执行驱逐
     */
    public boolean kickoutSession(SessionKey sessionKey, CloseStatus status) {
        if (sessionKey == null) {
            return false;
        }
        RealtimeSession session = sessionManager.removeAndClose(sessionKey, status != null ? status : RealtimeCloseStatus.USER_KICKED_OUT);
        return session != null;
    }

    /**
     * 主动驱逐指定会话下线（以默认 USER_KICKED_OUT 状态码）
     *
     * @param sessionKey 会话坐标
     * @return 是否成功执行驱逐
     */
    public boolean kickoutSession(SessionKey sessionKey) {
        return kickoutSession(sessionKey, RealtimeCloseStatus.USER_KICKED_OUT);
    }

    /**
     * 主动驱逐指定用户的所有在线设备会话下线（携带自定义关闭状态码）
     *
     * @param userId 用户标识
     * @param status 关闭状态原因
     * @return 成功驱逐的会话数量
     */
    public int kickoutUser(Object userId, CloseStatus status) {
        if (userId == null) {
            return 0;
        }
        List<RealtimeSession> sessions = sessionManager.getSessionsByUser(userId);
        int count = 0;
        CloseStatus closeStatus = status != null ? status : RealtimeCloseStatus.USER_KICKED_OUT;
        for (RealtimeSession s : sessions) {
            if (sessionManager.removeAndClose(s.getSessionKey(), closeStatus) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 主动驱逐指定用户的所有在线设备会话下线（以默认 USER_KICKED_OUT 状态码）
     *
     * @param userId 用户标识
     * @return 成功驱逐的会话数量
     */
    public int kickoutUser(Object userId) {
        return kickoutUser(userId, RealtimeCloseStatus.USER_KICKED_OUT);
    }

    // ==================== 状态度量与会话查询 (Metrics & Status) ====================

    /**
     * 查询指定用户当前是否在线（是否存在至少一个活跃会话）
     *
     * @param userId 用户标识
     */
    public boolean isUserOnline(Object userId) {
        if (userId == null) {
            return false;
        }
        List<RealtimeSession> sessions = sessionManager.getSessionsByUser(userId);
        return !sessions.isEmpty();
    }

    /**
     * 查询指定会话坐标当前是否处于活跃在线状态
     *
     * @param sessionKey 会话坐标
     */
    public boolean isSessionOnline(SessionKey sessionKey) {
        return sessionManager.containsSession(sessionKey);
    }

    /**
     * 获取当前节点在线总连接数
     */
    public int getOnlineCount() {
        return sessionManager.getOnlineCount();
    }

    /**
     * 获取当前节点在线独立用户数
     */
    public int getOnlineUserCount() {
        return sessionManager.getOnlineUserCount();
    }

    /**
     * 获取指定协议通道的在线连接数
     *
     * @param channelType 协议通道类型
     */
    public int getOnlineCountByChannel(ChannelType channelType) {
        return sessionManager.getOnlineCountByChannel(channelType);
    }

    /**
     * 获取指定用户的全量活跃会话列表
     *
     * @param userId 用户标识
     * @return 会话不可变列表（保底返回空列表，绝不返回 null）
     */
    public List<RealtimeSession> getSessionsByUser(Object userId) {
        return sessionManager.getSessionsByUser(userId);
    }

    /**
     * 获取全量活跃会话快照
     *
     * @return 全量会话不可变列表
     */
    public List<RealtimeSession> getAllSessions() {
        return sessionManager.getAllSessions();
    }

    /**
     * 精确获取指定会话
     *
     * @param sessionKey 会话坐标
     * @return 会话实体（若不存在或已关闭返回 null）
     */
    public RealtimeSession getSession(SessionKey sessionKey) {
        return sessionManager.getSession(sessionKey);
    }

    // ==================== 内部稳健发送核心 (Internal Defensive Send) ====================

    /**
     * 向候选会话集合批量执行稳健投递，支持按需谓词过滤
     *
     * @param sessions 候选会话列表
     * @param filter   过滤谓词条件（可空，为空时全量发送）
     * @param message  实时业务消息
     * @return 成功投递的会话数量
     */
    private int sendToSessions(Collection<RealtimeSession> sessions, Predicate<RealtimeSession> filter, RealtimeMessage<?> message) {
        if (sessions == null || sessions.isEmpty() || message == null) {
            return 0;
        }
        int successCount = 0;
        for (RealtimeSession session : sessions) {
            if (session != null && (filter == null || filter.test(session))) {
                if (doSend(session, message)) {
                    successCount++;
                }
            }
        }
        return successCount;
    }

    private boolean doSend(RealtimeSession session, RealtimeMessage<?> message) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(message);
            return true;
        } catch (IOException e) {
            log.warn("[Realtime] 发送消息发生网络/通道异常，执行安全排空: sessionKey={}, error={}",
                    session.getSessionKey(), e.getMessage());
            sessionManager.removeAndClose(session.getSessionKey(), CloseStatus.SERVER_ERROR);
            return false;
        }
    }
}
