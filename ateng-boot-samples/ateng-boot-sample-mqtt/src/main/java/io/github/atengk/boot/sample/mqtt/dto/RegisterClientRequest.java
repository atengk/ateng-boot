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
 * 动态注册命名 MQTT 客户端请求 DTO
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterClientRequest implements Serializable {

    /**
     * 目标客户端逻辑名称（唯一标识，不可为空白）
     */
    @NotBlank(message = "客户端逻辑名称 clientName 不能为空")
    private String clientName;

    /**
     * 目标 Broker 地址（可选；缺省继承主客户端配置的地址与凭据基线）
     */
    private String serverUri;

    /**
     * 物理 ClientId 前缀（可选；缺省使用 ateng-dynamic-&lt;clientName&gt; 并附加随机后缀）
     */
    private String clientIdPrefix;

    /**
     * MQTT Clean Start 标志（默认为 true）
     */
    @Builder.Default
    private Boolean cleanStart = Boolean.TRUE;

    /**
     * 连接建链超时时间（单位秒，允许范围 1..60，默认 5 秒）
     */
    @Min(value = 1, message = "连接超时至少为 1 秒")
    @Max(value = 60, message = "连接超时不能超过 60 秒")
    @Builder.Default
    private Integer connectionTimeoutSeconds = 5;
}
