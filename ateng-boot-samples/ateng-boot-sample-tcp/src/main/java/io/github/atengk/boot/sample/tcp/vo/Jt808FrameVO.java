package io.github.atengk.boot.sample.tcp.vo;

import java.io.Serializable;

/**
 * JT/T 808 复合二进制报文与校验码视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record Jt808FrameVO(
        String deviceId,
        Long seq,
        String routeKey,
        String hexFrame,
        int frameBytes,
        String bccHex,
        String crc16ModbusHex,
        String crc16CcittHex
) implements Serializable {}
