package io.github.atengk.boot.sample.mqtt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 受管命名 MQTT 客户端运行状态快照 VO（对外脱敏隔离底层句柄）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MqttClientSummaryVO implements Serializable {

    /**
     * 客户端逻辑名称
     */
    private String clientName;

    /**
     * 物理分配的 MQTT ClientId（可能含策略后缀）
     */
    private String clientId;

    /**
     * 目标 Broker 连接 URI
     */
    private String serverUri;

    /**
     * 当前物理连接是否处于在线状态
     */
    private boolean connected;

    /**
     * 协商使用的 MQTT 协议版本（如 5.0 / 3.1.1）
     */
    private String protocolVersion;

    /**
     * 脱敏后的健康探针明细字典
     */
    private Map<String, Object> healthDetails;
}
