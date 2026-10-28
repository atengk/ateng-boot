package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.tcp.model.TcpPacket;

/**
 * TCP 路由键 / 命令字提取策略 SPI 契约
 * <p>
 * 用于从入站 {@link TcpPacket} 中提取业务命令标识，以驱动路由分发。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@FunctionalInterface
public interface TcpRouteKeyExtractor {

    /**
     * 从数据包中提取路由命令字
     *
     * @param packet 入站数据包
     * @return 路由命令字；若无法提取则返回 {@code null}
     */
    String extract(TcpPacket packet);
}
