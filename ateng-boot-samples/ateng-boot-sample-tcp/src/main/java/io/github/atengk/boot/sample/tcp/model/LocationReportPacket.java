package io.github.atengk.boot.sample.tcp.model;

import java.io.Serializable;

/**
 * 车载终端定位遥测上报数据包
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class LocationReportPacket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务命令字
     */
    private String command = "LOCATION_REPORT";

    /**
     * 设备唯一硬件编号（IMEI / 车机号）
     */
    private String deviceId;

    /**
     * 消息流水号
     */
    private Long seq;

    /**
     * 经度
     */
    private Double longitude;

    /**
     * 纬度
     */
    private Double latitude;

    /**
     * 车速（km/h）
     */
    private Double speed;

    /**
     * 发动机工作状态（ON / OFF / ACC）
     */
    private String engineStatus;

    /**
     * 上报毫秒时间戳
     */
    private Long timestamp;

    public LocationReportPacket() {
    }

    public LocationReportPacket(String deviceId, Long seq, Double longitude, Double latitude, Double speed, Long timestamp) {
        this(deviceId, seq, longitude, latitude, speed, "ON", timestamp);
    }

    public LocationReportPacket(String deviceId, Long seq, Double longitude, Double latitude, Double speed, String engineStatus, Long timestamp) {
        this.deviceId = deviceId;
        this.seq = seq;
        this.longitude = longitude;
        this.latitude = latitude;
        this.speed = speed;
        this.engineStatus = engineStatus;
        this.timestamp = timestamp;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getSpeed() {
        return speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    public String getEngineStatus() {
        return engineStatus;
    }

    public void setEngineStatus(String engineStatus) {
        this.engineStatus = engineStatus;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "LocationReportPacket{" +
                "deviceId='" + deviceId + '\'' +
                ", seq=" + seq +
                ", longitude=" + longitude +
                ", latitude=" + latitude +
                ", speed=" + speed +
                ", timestamp=" + timestamp +
                '}';
    }
}
