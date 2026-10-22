package io.github.atengk.boot.ftp.properties;

import lombok.Data;

import java.time.Duration;

/**
 * FTP 客户端连接池配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
public class FtpPoolProperties {

    /**
     * 连接池最大总连接数
     */
    private int maxTotal = 16;

    /**
     * 连接池最大空闲连接数
     */
    private int maxIdle = 8;

    /**
     * 连接池最小空闲连接数
     */
    private int minIdle = 2;

    /**
     * 从连接池获取连接的最大等待超时时间（超过此时间抛出 PoolExhausted 异常）
     */
    private Duration maxWait = Duration.ofSeconds(5);

    /**
     * 从连接池借出连接时是否执行存活校验（发送 NOOP 心跳探针）
     */
    private boolean testOnBorrow = true;

    /**
     * 归还连接时是否执行有效性校验
     */
    private boolean testOnReturn = false;

    /**
     * 连接在连接池空闲时是否被后台驱逐线程校验
     */
    private boolean testWhileIdle = true;

    /**
     * 空闲连接后台驱逐线程的巡检周期
     */
    private Duration timeBetweenEvictionRuns = Duration.ofMinutes(1);

    /**
     * 连接在池中保持空闲而不被驱逐的最小存活时间
     */
    private Duration minEvictableIdleTime = Duration.ofMinutes(5);
}
