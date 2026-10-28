package io.github.atengk.boot.tcp.event;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.session.TcpSession;
import org.springframework.context.ApplicationEvent;

/**
 * TCP 会话断开注销领域事件
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpSessionClosedEvent extends ApplicationEvent {

    private final TcpSession session;
    private final CloseReason reason;

    public TcpSessionClosedEvent(Object source, TcpSession session, CloseReason reason) {
        super(source);
        this.session = session;
        this.reason = reason != null ? reason : CloseReason.NORMAL;
    }

    public TcpSession getSession() {
        return session;
    }

    public CloseReason getReason() {
        return reason;
    }
}
