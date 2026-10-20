package io.github.atengk.boot.http.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 全局 HTTP 物理连接池配置属性
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpPoolProperties {

    /**
     * 全局最大物理连接总数（默认：200）
     */
    private int maxConnections = 200;

    /**
     * 每个目标路由（域名+端口）的最大并发连接数（默认：50）
     */
    private int maxPerRoute = 50;

    /**
     * TCP 握手建联超时时间（默认：3秒）
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Socket 读取远端响应超时时间（默认：10秒）
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * 从物理连接池租借连接的最大等待超时时间（默认：2秒）
     * 超过该阈值快速失败抛出超时异常，防止高并发虚拟线程海量排队堆积撑爆堆内存
     */
    private Duration connectionRequestTimeout = Duration.ofSeconds(2);

    /**
     * 空闲连接最大保活驱逐时间（默认：60秒）
     * 超过此空闲时长的连接由后台守护线程主动物理关闭，防止云网关与防火墙静默断开导致的 Broken pipe 异常
     */
    private Duration idleTimeout = Duration.ofSeconds(60);

    /**
     * 连接从池中租借前判定是否需要保活探测的空闲间隔阈值（默认：2秒）
     */
    private Duration validateAfterInactivity = Duration.ofSeconds(2);
}
