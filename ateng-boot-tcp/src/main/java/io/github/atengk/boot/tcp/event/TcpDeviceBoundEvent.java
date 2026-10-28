package io.github.atengk.boot.tcp.event;

import io.github.atengk.boot.tcp.session.TcpSession;
import org.springframework.context.ApplicationEvent;

/**
 * TCP 设备标识与会话绑定成功领域事件
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpDeviceBoundEvent extends ApplicationEvent {

    private final TcpSession session;
    private final String deviceId;

    public TcpDeviceBoundEvent(Object source, TcpSession session, String deviceId) {
        super(source);
        this.session = session;
        this.deviceId = deviceId;
    }

    public TcpSession getSession() {
        return session;
    }

    public String getDeviceId() {
        return deviceId;
    }
}
