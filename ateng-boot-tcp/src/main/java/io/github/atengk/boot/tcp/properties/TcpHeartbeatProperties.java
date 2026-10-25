package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * TCP 客户端主动心跳保活配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpHeartbeatProperties {

    /**
     * 是否启用客户端主动定时发送心跳 Ping（默认 false）
     */
    private boolean enabled = false;

    /**
     * 心跳发送周期（默认 30s）
     */
    private Duration interval = Duration.ofSeconds(30);

    /**
     * 心跳请求载荷内容（默认 "PING"）
     */
    private String payload = "PING";
}
