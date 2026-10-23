package io.github.atengk.boot.sample.mqtt.model;

import java.io.Serializable;

/**
 * 物联网设备上行遥测业务载荷实体
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DeviceTelemetry implements Serializable {

    private String deviceId;
    private double temperature;
    private double humidity;
    private long timestamp;

    public DeviceTelemetry() {}

    public DeviceTelemetry(String deviceId, double temperature, double humidity, long timestamp) {
        this.deviceId = deviceId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.timestamp = timestamp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public double getHumidity() {
        return humidity;
    }

    public void setHumidity(double humidity) {
        this.humidity = humidity;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "DeviceTelemetry{" +
                "deviceId='" + deviceId + '\'' +
                ", temperature=" + temperature +
                ", humidity=" + humidity +
                ", timestamp=" + timestamp +
                '}';
    }
}
