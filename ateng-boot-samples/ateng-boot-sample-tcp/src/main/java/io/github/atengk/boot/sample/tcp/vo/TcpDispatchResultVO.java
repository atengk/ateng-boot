package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * 下行单播/组播/广播分发结果视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpDispatchResultVO(
        String scope,
        String target,
        boolean clusterMode,
        String bridgeType,
        int localSessionCount,
        boolean dispatched,
        String payloadSummary
) implements Serializable {}
