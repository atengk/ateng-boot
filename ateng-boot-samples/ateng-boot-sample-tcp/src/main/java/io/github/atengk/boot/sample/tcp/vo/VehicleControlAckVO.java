package io.github.atengk.boot.sample.tcp.vo;

import io.github.atengk.boot.sample.tcp.model.VehicleControlAck;

import java.io.Serializable;

/**
 * 车机远程控制下发执行回执视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
public record VehicleControlAckVO(
        String requestId,
        String deviceId,
        String command,
        String status,
        String message
) implements Serializable {

    public static VehicleControlAckVO from(VehicleControlAck ack) {
        if (ack == null) {
            return null;
        }
        return new VehicleControlAckVO(
                ack.getRequestId(),
                ack.getDeviceId(),
                ack.getCommand(),
                ack.getStatus(),
                ack.getMessage()
        );
    }
}
