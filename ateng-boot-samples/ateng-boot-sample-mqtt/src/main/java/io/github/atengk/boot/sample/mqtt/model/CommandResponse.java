package io.github.atengk.boot.sample.mqtt.model;

import java.io.Serializable;

/**
 * 边缘设备下行控制响应实体
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class CommandResponse implements Serializable {

    private String commandId;
    private boolean success;
    private String message;
    private long executeTime;

    public CommandResponse() {}

    public CommandResponse(String commandId, boolean success, String message, long executeTime) {
        this.commandId = commandId;
        this.success = success;
        this.message = message;
        this.executeTime = executeTime;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(long executeTime) {
        this.executeTime = executeTime;
    }

    @Override
    public String toString() {
        return "CommandResponse{" +
                "commandId='" + commandId + '\'' +
                ", success=" + success +
                ", message='" + message + '\'' +
                ", executeTime=" + executeTime +
                '}';
    }
}
