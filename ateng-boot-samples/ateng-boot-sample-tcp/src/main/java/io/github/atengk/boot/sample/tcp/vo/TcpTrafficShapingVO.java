package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * 全局流量整形（GlobalTrafficShapingHandler）实时指标与限速阈值视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpTrafficShapingVO(
        String serverName,
        boolean enabled,
        long writeLimitBytesPerSec,
        long readLimitBytesPerSec,
        long cumulativeWrittenBytes,
        long cumulativeReadBytes,
        long lastWriteThroughputBytesPerSec,
        long lastReadThroughputBytesPerSec
) implements Serializable {}
