package io.github.atengk.boot.tcp.spi;

import io.netty.channel.Channel;
import org.springframework.core.Ordered;

/**
 * Netty Channel 流水线定制逃生舱 SPI
 * <p>
 * 允许高级开发者自由介入并在 Netty Pipeline 中注册自定义 Handler。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public interface TcpChannelCustomizer extends Ordered {

    /**
     * 定制或编排 Netty 通道
     *
     * @param channel Netty 通道对象
     */
    void customize(Channel channel);

    /**
     * 针对特定角色与命名组件定制 Netty 通道
     *
     * @param channel Netty 通道对象
     * @param role    组件角色（SERVER / CLIENT）
     * @param name    组件自定义名称（如 "default", "jt808", "clientA"）
     */
    default void customize(Channel channel, String role, String name) {
        customize(channel);
    }

    @Override
    default int getOrder() {
        return 0;
    }
}
