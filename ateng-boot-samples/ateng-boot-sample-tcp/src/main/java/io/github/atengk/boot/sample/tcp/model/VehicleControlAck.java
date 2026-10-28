package io.github.atengk.boot.sample.tcp.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车机远程控制下发执行回执模型
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleControlAck implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联请求流水号
     */
    private String requestId;

    /**
     * 设备唯一硬件标识
     */
    private String deviceId;

    /**
     * 执行的控制指令码
     */
    private String command;

    /**
     * 执行结果状态（SUCCESS / FAILED）
     */
    private String status;

    /**
     * 状态回执描述信息
     */
    private String message;
}
