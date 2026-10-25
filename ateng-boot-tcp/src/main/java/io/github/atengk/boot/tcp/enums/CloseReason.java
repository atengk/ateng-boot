package io.github.atengk.boot.tcp.enums;

/**
 * TCP 会话通道关闭原因枚举
 *
 * @author Ateng
 * @since 2026-10-26
 */
public enum CloseReason {

    /**
     * 正常断开连接（对端主动 FIN/RST 或 EOF）
     */
    NORMAL,

    /**
     * 空闲心跳超时主动断开
     */
    IDLE_TIMEOUT,

    /**
     * 设备重新上线被踢下线（踢旧留新置换）
     */
    KICK_OUT,

    /**
     * 服务端并发连接数达到硬上限（超载熔断拒绝）
     */
    OVERLOAD,

    /**
     * 通道读写或协议解码异常强制断开
     */
    ERROR,

    /**
     * 服务端接收停机信号优雅排空主动关闭
     */
    SERVER_SHUTDOWN
}
