package io.github.atengk.boot.tcp.event;

import io.github.atengk.boot.tcp.session.TcpSession;
import io.netty.handler.timeout.IdleState;
import org.springframework.context.ApplicationEvent;

/**
 * TCP 会话空闲心跳触发领域事件
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpSessionIdleEvent extends ApplicationEvent {

    private final TcpSession session;
    private final IdleState state;

    public TcpSessionIdleEvent(Object source, TcpSession session, IdleState state) {
        super(source);
        this.session = session;
        this.state = state;
    }

    public TcpSession getSession() {
        return session;
    }

    public IdleState getState() {
        return state;
    }
}
