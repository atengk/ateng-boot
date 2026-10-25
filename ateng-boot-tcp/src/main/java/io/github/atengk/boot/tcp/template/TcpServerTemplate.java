package io.github.atengk.boot.tcp.template;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;

/**
 * TCP 服务端核心操作门面
 * <p>
 * 面向服务端业务场景，提供单播（指定会话或设备）、全局广播与分组组播等高阶对称操作。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpServerTemplate {

    private static final Logger log = LoggerFactory.getLogger(TcpServerTemplate.class);

    private final TcpSessionManager sessionManager;

    public TcpServerTemplate(TcpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 向指定会话发送消息
     *
     * @param sessionId 会话唯一标识
     * @param payload   报文载荷（支持 TcpPacket, TcpPayload, byte[], String 或任意 POJO）
     */
    public void send(String sessionId, Object payload) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        TcpSession session = sessionManager.getSession(sessionId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("未找到对应的活跃 TCP 会话: sessionId=" + sessionId);
        }
        Object msg = wrapPayload(payload);
        session.send(msg);
    }

    /**
     * 向指定业务设备标识发送消息
     *
     * @param deviceId 业务设备唯一编号
     * @param payload  报文载荷
     */
    public void sendToDevice(String deviceId, Object payload) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("设备未在线或连接已断开: deviceId=" + deviceId);
        }
        Object msg = wrapPayload(payload);
        session.send(msg);
    }

    /**
     * 向当前所有在线的活跃会话进行全局广播
     *
     * @param payload 报文载荷
     */
    public void broadcast(Object payload) {
        Object msg = wrapPayload(payload);
        Collection<TcpSession> sessions = sessionManager.getAllSessions();
        for (TcpSession session : sessions) {
            if (session.isActive()) {
                try {
                    session.send(msg);
                } catch (Exception ex) {
                    log.warn("向会话广播消息失败: sessionId={}", session.getSessionId(), ex);
                }
            }
        }
    }

    /**
     * 向指定业务逻辑分组的所有在线会话进行组播
     *
     * @param group   分组名称
     * @param payload 报文载荷
     */
    public void sendToGroup(String group, Object payload) {
        if (group == null) {
            return;
        }
        Object msg = wrapPayload(payload);
        Set<TcpSession> sessions = sessionManager.getGroupSessions(group);
        for (TcpSession session : sessions) {
            if (session.isActive()) {
                try {
                    session.send(msg);
                } catch (Exception ex) {
                    log.warn("向分组 [{}] 会话组播消息失败: sessionId={}", group, session.getSessionId(), ex);
                }
            }
        }
    }

    /**
     * 获取当前在线活跃连接总数
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return sessionManager.getActiveCount();
    }

    /**
     * 获取会话管理器底座实例
     *
     * @return 会话管理器
     */
    public TcpSessionManager getSessionManager() {
        return sessionManager;
    }

    private Object wrapPayload(Object payload) {
        if (payload == null) {
            return TcpPayload.empty();
        }
        if (payload instanceof TcpPacket || payload instanceof TcpPayload ||
                payload instanceof byte[] || payload instanceof String) {
            return payload;
        }
        return TcpPayload.fromJson(payload);
    }
}
