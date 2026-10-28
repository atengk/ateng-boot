package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * TCP 空闲心跳检测配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpIdleProperties {

    /**
     * 读空闲超时时间（在此时间内未收到对端报文视为读空闲，默认 60s，设为 0 或 ZERO 表示不检测）
     */
    private Duration readerIdleTime = Duration.ofSeconds(60);

    /**
     * 写空闲超时时间（在此时间内未向对端发送报文视为写空闲，默认 0s 表示不检测）
     */
    private Duration writerIdleTime = Duration.ZERO;

    /**
     * 全空闲超时时间（在此时间内读写均无交互视为全空闲，默认 0s 表示不检测）
     */
    private Duration allIdleTime = Duration.ZERO;

    /**
     * 发生空闲超时时是否自动断开连接通道（默认 true）
     */
    private boolean autoClose = true;
}
