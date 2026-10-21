package io.github.atengk.boot.realtime.event;

import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.io.Serial;
import java.util.Objects;

/**
 * 实时会话成功建立物理连接后发布的 Spring 原生应用事件
 * <p>
 * 携带当前受管统一会话实体，支持业务侧使用 @EventListener 监听并处理用户上线、状态刷新或资源预占。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public class RealtimeConnectedEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前已建立并托管的统一会话实体
     */
    private final RealtimeSession session;

    public RealtimeConnectedEvent(Object source, RealtimeSession session) {
        super(source);
        this.session = Objects.requireNonNull(session, "session must not be null");
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
