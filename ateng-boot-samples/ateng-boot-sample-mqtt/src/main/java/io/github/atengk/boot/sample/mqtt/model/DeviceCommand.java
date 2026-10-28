package io.github.atengk.boot.sample.mqtt.model;

import java.io.Serializable;
import java.util.Map;

/**
 * 边缘设备下行控制指令实体
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DeviceCommand implements Serializable {

    private String commandId;
    private String action;
    private Map<String, Object> params;

    public DeviceCommand() {}

    public DeviceCommand(String commandId, String action, Map<String, Object> params) {
        this.commandId = commandId;
        this.action = action;
        this.params = params;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    @Override
    public String toString() {
        return "DeviceCommand{" +
                "commandId='" + commandId + '\'' +
                ", action='" + action + '\'' +
                ", params=" + params +
                '}';
    }
}
