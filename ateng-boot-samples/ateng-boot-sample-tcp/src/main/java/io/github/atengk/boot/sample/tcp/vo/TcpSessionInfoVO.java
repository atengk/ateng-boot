package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;
import java.util.Map;

/**
 * TCP 长连接会话只读视图对象（绝对隔离 TcpSession 与底层 Netty Channel 对象）
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpSessionInfoVO(
        String sessionId,
        String deviceId,
        String remoteAddress,
        String localAddress,
        boolean active,
        boolean writable,
        long pendingWriteBytes,
        String backpressurePolicy,
        Long backpressureTimeoutMs,
        long connectedAt,
        long lastActiveTime,
        String closeReason,
        Map<String, Object> attributes
) implements Serializable {}
