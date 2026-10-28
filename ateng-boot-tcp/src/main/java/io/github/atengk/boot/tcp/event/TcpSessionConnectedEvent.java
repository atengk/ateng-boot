package io.github.atengk.boot.tcp.event;

import io.github.atengk.boot.tcp.session.TcpSession;
import org.springframework.context.ApplicationEvent;

/**
 * TCP 会话连接建立成功领域事件
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpSessionConnectedEvent extends ApplicationEvent {

    private final TcpSession session;

    public TcpSessionConnectedEvent(Object source, TcpSession session) {
        super(source);
        this.session = session;
    }

    public TcpSession getSession() {
        return session;
    }
}
