package io.github.atengk.boot.sample.mqtt.dto;

import java.io.Serializable;

/**
 * 工业国标十六进制报文转译回执
 *
 * @author Ateng
 * @since 2026-11-10
 */
public record HexPublishResponse(
        String deviceId,
        String topic,
        String originalHex,
        String formattedHex,
        int byteLength
) implements Serializable {}
