package io.github.atengk.boot.realtime.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 实时通信底层物理通道协议类型枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
@RequiredArgsConstructor
public enum ChannelType {

    /**
     * WebSocket 全双工长连接通道
     */
    WEBSOCKET("websocket", "WebSocket 全双工通道"),

    /**
     * Server-Sent Events 单向长连接通道
     */
    SSE("sse", "SSE 单向长连接流通道");

    private final String code;
    private final String description;
}
