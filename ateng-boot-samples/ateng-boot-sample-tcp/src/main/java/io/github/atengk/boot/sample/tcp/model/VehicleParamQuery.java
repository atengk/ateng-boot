package io.github.atengk.boot.sample.tcp.model;

import java.io.Serializable;

/**
 * 车载终端参数查询指令报文
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class VehicleParamQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务命令字
     */
    private String command = "PARAM_QUERY";

    /**
     * RPC 请求关联流水号
     */
    private String requestId;

    /**
     * 目标设备唯一编号
     */
    private String deviceId;

    /**
     * 参数查询类型（如 SYSTEM, NETWORK, BATTERY）
     */
    private String paramType;

    public VehicleParamQuery() {
    }

    public VehicleParamQuery(String requestId, String deviceId, String paramType) {
        this.requestId = requestId;
        this.deviceId = deviceId;
        this.paramType = paramType;
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

    public String getParamType() {
        return paramType;
    }

    public void setParamType(String paramType) {
        this.paramType = paramType;
    }

    @Override
    public String toString() {
        return "VehicleParamQuery{" +
                "requestId='" + requestId + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", paramType='" + paramType + '\'' +
                '}';
    }
}
