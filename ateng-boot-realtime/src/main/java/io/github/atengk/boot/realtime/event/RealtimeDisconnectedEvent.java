package io.github.atengk.boot.realtime.event;

import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.springframework.web.socket.CloseStatus;

import java.io.Serial;
import java.util.Objects;

/**
 * 实时会话关闭断开后发布的 Spring 原生应用事件
 * <p>
 * 携带当前受管统一会话实体及关闭状态原因，支持业务侧使用 @EventListener 监听处理用户下线、协作锁释放或离线审计。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public class RealtimeDisconnectedEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 已断开注销的受管统一会话实体
     */
    private final RealtimeSession session;

    /**
     * 关闭状态码及原因说明（可空）
     */
    private final CloseStatus closeStatus;

    public RealtimeDisconnectedEvent(Object source, RealtimeSession session, CloseStatus closeStatus) {
        super(source);
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.closeStatus = closeStatus;
    }

    /**
     * 获取会话三维唯一坐标
     */
    public SessionKey getSessionKey() {
        return session.getSessionKey();
    }

    /**
     * 获取用户唯一标识
     */
    public String getUserId() {
        return session.getUserId();
    }

    /**
     * 获取通道协议类型
     */
    public ChannelType getChannelType() {
        return session.getChannelType();
    }

    /**
     * 获取归属多租户标识
     */
    public Long getTenantId() {
        return session.getTenantId();
    }
}
