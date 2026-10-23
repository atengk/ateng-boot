package io.github.atengk.boot.ssh.properties;

import lombok.Data;

import java.time.Duration;

/**
 * SSH 连接会话缓存池配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class SshPoolProperties {

    /**
     * 连接池最大活跃会话容量上限
     */
    private int maxSessions = 64;

    /**
     * 空闲会话存活淘汰超时时间
     */
    private Duration idleTimeout = Duration.ofMinutes(5);

    /**
     * 保持长连接心跳保活探针发送周期
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);
}
