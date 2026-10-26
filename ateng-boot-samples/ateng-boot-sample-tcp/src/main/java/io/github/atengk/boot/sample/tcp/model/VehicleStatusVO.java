package io.github.atengk.boot.sample.tcp.model;

import java.io.Serializable;

/**
 * 车机在线状态视图对象 (VO)
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class VehicleStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 车机唯一标识
     */
    private String deviceId;

    /**
     * 是否在线
     */
    private Boolean online;

    /**
     * 最新上报经度
     */
    private Double lastLongitude;

    /**
     * 最新上报纬度
     */
    private Double lastLatitude;

    /**
     * 最新车速（km/h）
     */
    private Double lastSpeed;

    /**
     * 最后心跳/定位更新时间戳
     */
    private Long updateTime;

    public VehicleStatusVO() {
    }

    public VehicleStatusVO(String deviceId, Boolean online, Double lastLongitude, Double lastLatitude, Double lastSpeed, Long updateTime) {
        this.deviceId = deviceId;
        this.online = online;
        this.lastLongitude = lastLongitude;
        this.lastLatitude = lastLatitude;
        this.lastSpeed = lastSpeed;
        this.updateTime = updateTime;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Double getLastLongitude() {
        return lastLongitude;
    }

    public void setLastLongitude(Double lastLongitude) {
        this.lastLongitude = lastLongitude;
    }

    public Double getLastLatitude() {
        return lastLatitude;
    }

    public void setLastLatitude(Double lastLatitude) {
        this.lastLatitude = lastLatitude;
    }

    public Double getLastSpeed() {
        return lastSpeed;
    }

    public void setLastSpeed(Double lastSpeed) {
        this.lastSpeed = lastSpeed;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "VehicleStatusVO{" +
                "deviceId='" + deviceId + '\'' +
                ", online=" + online +
                ", lastLongitude=" + lastLongitude +
                ", lastLatitude=" + lastLatitude +
                ", lastSpeed=" + lastSpeed +
                ", updateTime=" + updateTime +
                '}';
    }
}
