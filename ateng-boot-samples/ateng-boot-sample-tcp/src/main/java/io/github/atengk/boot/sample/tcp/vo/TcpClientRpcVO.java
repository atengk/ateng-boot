package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * 客户端上行 RPC 响应回执视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpClientRpcVO(
        String clientName,
        String command,
        String requestId,
        boolean asyncMode,
        String responseCommand,
        String responseText,
        long elapsedMs
) implements Serializable {}
