package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * TCP 托管客户端实例只读视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpClientInfoVO(
        String name,
        String host,
        int port,
        boolean running,
        boolean connected,
        String sessionId,
        String framingMode,
        boolean sslEnabled,
        boolean heartbeatEnabled,
        boolean reconnectEnabled
) implements Serializable {}
