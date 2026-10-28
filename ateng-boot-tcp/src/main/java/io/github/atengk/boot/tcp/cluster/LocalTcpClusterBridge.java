package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.Collection;

/**
 * 本地单机 TCP 推送桥接实现
 * <p>
 * 默认推进行单机本地直发，当目标设备未在本机连接时记录警告日志并安全降级。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class LocalTcpClusterBridge implements TcpClusterBridge {

    private static final Logger log = LoggerFactory.getLogger(LocalTcpClusterBridge.class);

    private final TcpSessionManager sessionManager;

    public LocalTcpClusterBridge(TcpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean sendToCluster(String deviceId, Object payload) {
        Assert.hasText(deviceId, "deviceId 不能为空");
        Assert.notNull(payload, "payload 不能为空");

        TcpSession session = sessionManager.getSessionByDeviceId(deviceId);
        if (session != null && session.isActive()) {
            session.send(payload);
            return true;
        }

        log.debug("目标设备 [{}] 未在本机建立连接，单机模式无法跨节点推送", deviceId);
        return false;
    }

    @Override
    public boolean broadcastToCluster(Object payload) {
        Assert.notNull(payload, "payload 不能为空");
        Collection<TcpSession> sessions = sessionManager.getAllSessions();
        if (sessions.isEmpty()) {
            return false;
        }
        for (TcpSession session : sessions) {
            if (session.isActive()) {
                session.send(payload);
            }
        }
        return true;
    }
}
