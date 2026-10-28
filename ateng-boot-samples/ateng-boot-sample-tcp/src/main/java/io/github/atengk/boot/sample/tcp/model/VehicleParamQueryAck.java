package io.github.atengk.boot.sample.tcp.model;

import java.io.Serializable;

/**
 * 车载终端参数查询应答报文
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class VehicleParamQueryAck implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务命令字
     */
    private String command = "PARAM_QUERY_ACK";

    /**
     * RPC 请求关联流水号（与请求方对齐）
     */
    private String requestId;

    /**
     * 响应设备编号
     */
    private String deviceId;

    /**
     * 车机固件版本
     */
    private String firmwareVersion;

    /**
     * 当前电量百分比 (0-100)
     */
    private Integer batteryLevel;

    /**
     * 运行状态（如 NORMAL, WARNING）
     */
    private String status;

    public VehicleParamQueryAck() {
    }

    public VehicleParamQueryAck(String requestId, String deviceId, String firmwareVersion, Integer batteryLevel, String status) {
        this.requestId = requestId;
        this.deviceId = deviceId;
        this.firmwareVersion = firmwareVersion;
        this.batteryLevel = batteryLevel;
        this.status = status;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public Integer getBatteryLevel() {
        return batteryLevel;
    }

    public void setBatteryLevel(Integer batteryLevel) {
        this.batteryLevel = batteryLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "VehicleParamQueryAck{" +
                "requestId='" + requestId + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", firmwareVersion='" + firmwareVersion + '\'' +
                ", batteryLevel=" + batteryLevel +
                ", status='" + status + '\'' +
                '}';
    }
}
