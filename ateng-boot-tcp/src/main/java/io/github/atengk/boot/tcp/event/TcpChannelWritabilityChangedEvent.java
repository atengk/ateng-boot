package io.github.atengk.boot.tcp.event;

import io.github.atengk.boot.tcp.session.TcpSession;
import org.springframework.context.ApplicationEvent;

/**
 * TCP 通道可写状态变迁领域事件
 * <p>
 * 当底层 Netty 网络通道出站缓冲区触达高水位（不可写）或排空回落至低水位（恢复可写）时向 Spring 容器发布。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class TcpChannelWritabilityChangedEvent extends ApplicationEvent {

    private final TcpSession session;
    private final boolean writable;

    public TcpChannelWritabilityChangedEvent(Object source, TcpSession session, boolean writable) {
        super(source);
        this.session = session;
        this.writable = writable;
    }

    /**
     * 获取发生状态跃迁的 TCP 会话
     *
     * @return 会话实体
     */
    public TcpSession getSession() {
        return session;
    }

    /**
     * 当前通道是否处于可写状态
     *
     * @return true 表示低于高水位、可安全写入；false 表示触达高水位背压饱和
     */
    public boolean isWritable() {
        return writable;
    }
}
