package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpDeviceBoundEvent;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 统一标准 TCP 会话管理器
 * <p>
 * 专职负责 TCP 会话的注册、注销、生命周期管理、设备标识（deviceId）双向映射绑定，
 * 以及重连场景下的“踢旧留新（Kick-out）”确定性置换治理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpSessionManager implements ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(TcpSessionManager.class);

    private final ConcurrentMap<String, TcpSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> deviceToSession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> groupSessions = new ConcurrentHashMap<>();

    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 注册新建立的 TCP 会话
     *
     * @param session TCP 会话对象
     */
    public void register(TcpSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        sessions.put(session.getSessionId(), session);
        log.debug("TCP 会话注册成功: sessionId={}, remote={}", session.getSessionId(), session.getRemoteAddress());

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TcpSessionConnectedEvent(this, session));
        }
    }

    /**
     * 注销并移除 TCP 会话
     *
     * @param sessionId 会话唯一标识
     * @param reason    断开关闭原因
     */
    public void unregister(String sessionId, CloseReason reason) {
        if (sessionId == null) {
            return;
        }
        TcpSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }

        session.setCloseReason(reason);

        // 清理解除绑定的设备标识
        String deviceId = session.getDeviceId();
        if (deviceId != null) {
            deviceToSession.remove(deviceId, sessionId);
        }

        // 清理分组映射
        for (Set<String> set : groupSessions.values()) {
            set.remove(sessionId);
        }

        log.debug("TCP 会话已注销: sessionId={}, deviceId={}, reason={}", sessionId, deviceId, reason);

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TcpSessionClosedEvent(this, session, reason));
        }
    }

    /**
     * 将当前会话与业务设备标识（deviceId）进行双向映射绑定
     * <p>
     * <b>踢旧留新策略 (Kick-out Strategy)</b>: 若该 deviceId 之前已绑定在其他活跃会话上（如网络闪断后设备重连），
     * 将主动向旧会话发送下线断开信号并注销，再将新通道与设备绑定，防止僵尸连接占用设备标识。
     * </p>
     *
     * @param sessionId 当前物理会话 ID
     * @param deviceId  业务设备唯一编号
     */
    public void bind(String sessionId, String deviceId) {
        if (sessionId == null || deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 与 deviceId 均不能为空");
        }
        TcpSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("未找到对应的活跃 TCP 会话: sessionId=" + sessionId);
        }

        // 检测是否存在旧会话绑定
        String oldSessionId = deviceToSession.put(deviceId, sessionId);
        if (oldSessionId != null && !oldSessionId.equals(sessionId)) {
            TcpSession oldSession = sessions.get(oldSessionId);
            if (oldSession != null && oldSession.isActive()) {
                log.warn("检测到设备 [{}] 重新连接上线，触发“踢旧留新”置换: 正在切断旧会话 [{}], remote={}",
                        deviceId, oldSessionId, oldSession.getRemoteAddress());
                oldSession.close(CloseReason.KICK_OUT);
                unregister(oldSessionId, CloseReason.KICK_OUT);
            }
        }

        session.setDeviceId(deviceId);
        log.info("设备标识与 TCP 会话绑定成功: deviceId={}, sessionId={}", deviceId, sessionId);

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TcpDeviceBoundEvent(this, session, deviceId));
        }
    }

    /**
     * 解除设备标识与会话的绑定映射
     *
     * @param deviceId 业务设备编号
     */
    public void unbind(String deviceId) {
        if (deviceId == null) {
            return;
        }
        String sessionId = deviceToSession.remove(deviceId);
        if (sessionId != null) {
            TcpSession session = sessions.get(sessionId);
            if (session != null) {
                session.setDeviceId(null);
            }
            log.debug("设备标识已解绑: deviceId={}, sessionId={}", deviceId, sessionId);
        }
    }

    /**
     * 根据会话 ID 检索会话
     *
     * @param sessionId 会话唯一标识
     * @return 会话对象（不存在返回 null）
     */
    public TcpSession getSession(String sessionId) {
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 根据设备标识检索绑定的会话
     *
     * @param deviceId 业务设备编号
     * @return 会话对象（未绑定或不存在返回 null）
     */
    public TcpSession getSessionByDeviceId(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        String sessionId = deviceToSession.get(deviceId);
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 获取当前所有受管会话集合
     *
     * @return 只读会话集合
     */
    public Collection<TcpSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * 获取当前活跃且未断开的连接总数
     *
     * @return 活跃连接数
     */
    public int getActiveCount() {
        int count = 0;
        for (TcpSession s : sessions.values()) {
            if (s.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 将指定会话加入业务逻辑分组
     *
     * @param group     分组名称
     * @param sessionId 会话 ID
     */
    public void addToGroup(String group, String sessionId) {
        if (group == null || sessionId == null) {
            return;
        }
        groupSessions.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    /**
     * 将指定会话从业务逻辑分组移除
     *
     * @param group     分组名称
     * @param sessionId 会话 ID
     */
    public void removeFromGroup(String group, String sessionId) {
        if (group == null || sessionId == null) {
            return;
        }
        Set<String> set = groupSessions.get(group);
        if (set != null) {
            set.remove(sessionId);
        }
    }

    /**
     * 获取指定分组下所有活跃的会话集合
     *
     * @param group 分组名称
     * @return 活跃会话集合（保底非空集合）
     */
    public Set<TcpSession> getGroupSessions(String group) {
        if (group == null) {
            return Collections.emptySet();
        }
        Set<String> ids = groupSessions.get(group);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        Set<TcpSession> result = new HashSet<>();
        for (String id : ids) {
            TcpSession s = sessions.get(id);
            if (s != null && s.isActive()) {
                result.add(s);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 主动关闭指定会话
     *
     * @param sessionId 会话 ID
     * @param reason    关闭原因
     */
    public void closeSession(String sessionId, CloseReason reason) {
        TcpSession session = getSession(sessionId);
        if (session != null) {
            session.close(reason);
            unregister(sessionId, reason);
        }
    }

    /**
     * 优雅注销并关闭所有活跃会话（用于停机排空）
     *
     * @param reason 关闭原因
     */
    public void closeAllSessions(CloseReason reason) {
        for (TcpSession session : new ArrayList<>(sessions.values())) {
            try {
                session.close(reason);
                unregister(session.getSessionId(), reason);
            } catch (Exception ex) {
                log.warn("关闭会话时发生异常: sessionId={}", session.getSessionId(), ex);
            }
        }
    }
}
