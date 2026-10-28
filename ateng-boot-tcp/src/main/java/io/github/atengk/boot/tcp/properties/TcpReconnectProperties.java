package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * TCP 客户端断线指数退避自愈重连配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpReconnectProperties {

    /**
     * 是否启用断线自动重连（默认 true）
     */
    private boolean enabled = true;

    /**
     * 初始重连退避等待时间（默认 1s）
     */
    private Duration initialInterval = Duration.ofSeconds(1);

    /**
     * 最大重连退避等待上限时间（默认 30s）
     */
    private Duration maxInterval = Duration.ofSeconds(30);

    /**
     * 指数退避增长乘数（默认 2.0）
     */
    private double multiplier = 2.0;

    /**
     * 最大尝试重连次数（默认 0 表示无限尝试自愈）
     */
    private int maxAttempts = 0;
}
