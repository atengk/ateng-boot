package io.github.atengk.boot.sample.tcp.vo;

import io.github.atengk.boot.sample.tcp.model.VehicleParamQueryAck;

import java.io.Serializable;

/**
 * 车载终端运行参数查询应答视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record VehicleParamQueryAckVO(
        String command,
        String requestId,
        String deviceId,
        String firmwareVersion,
        Integer batteryLevel,
        String status
) implements Serializable {

    public static VehicleParamQueryAckVO from(VehicleParamQueryAck ack) {
        if (ack == null) {
            return null;
        }
        return new VehicleParamQueryAckVO(
                ack.getCommand(),
                ack.getRequestId(),
                ack.getDeviceId(),
                ack.getFirmwareVersion(),
                ack.getBatteryLevel(),
                ack.getStatus()
        );
    }
}
