package io.github.atengk.boot.sample.tcp.vo;

import io.github.atengk.boot.sample.tcp.model.LocationReportPacket;

import java.io.Serializable;

/**
 * 车载终端定位遥测报文解码视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record LocationReportVO(
        String command,
        String deviceId,
        Long seq,
        Double longitude,
        Double latitude,
        Double speed,
        String engineStatus,
        Long timestamp
) implements Serializable {

    public static LocationReportVO from(LocationReportPacket packet) {
        if (packet == null) {
            return null;
        }
        return new LocationReportVO(
                packet.getCommand(),
                packet.getDeviceId(),
                packet.getSeq(),
                packet.getLongitude(),
                packet.getLatitude(),
                packet.getSpeed(),
                packet.getEngineStatus(),
                packet.getTimestamp()
        );
    }
}
