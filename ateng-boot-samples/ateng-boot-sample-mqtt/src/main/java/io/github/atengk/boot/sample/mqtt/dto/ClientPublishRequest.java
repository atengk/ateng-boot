package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 命名客户端定向发信请求 DTO（通过 MqttTemplate.use(clientName) 绑定视图投递）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientPublishRequest implements Serializable {

    /**
     * 已注册的命名客户端逻辑名称
     */
    @NotBlank(message = "客户端逻辑名称 clientName 不能为空")
    private String clientName;

    /**
     * 目标 MQTT 发布主题
     */
    @NotBlank(message = "目标主题 topic 不能为空")
    private String topic;

    /**
     * 待发布文本载荷内容
     */
    @NotBlank(message = "报文载荷 payload 不能为空")
    private String payload;

    /**
     * MQTT 服务质量等级（仅允许 0、1、2，默认 1）
     */
    @Min(value = 0, message = "QoS 不能小于 0")
    @Max(value = 2, message = "QoS 不能大于 2")
    @Builder.Default
    private Integer qos = 1;

    /**
     * 是否为 Broker 保留消息（默认 false）
     */
    @Builder.Default
    private Boolean retained = Boolean.FALSE;
}
