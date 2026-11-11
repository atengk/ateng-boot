package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * 客户端单向发送回执视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record TcpClientSendVO(
        String clientName,
        String payloadType,
        String command,
        boolean sent,
        int payloadBytes
) implements Serializable {}
