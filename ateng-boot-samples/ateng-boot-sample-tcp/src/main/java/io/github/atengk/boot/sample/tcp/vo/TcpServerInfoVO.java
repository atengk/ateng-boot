package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;
import java.util.List;

/**
 * TCP 服务端实例只读视图对象（隔离内部 Netty 与 ServerBootstrap 状态）
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpServerInfoVO(
        String name,
        boolean running,
        String host,
        int configuredPort,
        int actualPort,
        String framingMode,
        boolean sslEnabled,
        String clientAuth,
        int activeConnections,
        boolean ipFilterEnabled,
        List<String> whitelist,
        List<String> blacklist,
        boolean trafficShapingEnabled
) implements Serializable {}
