package io.github.atengk.boot.mqtt.credentials;

import io.github.atengk.boot.mqtt.properties.MqttClientProperties;

/**
 * MQTT 客户端动态认证凭据供给 SPI
 * <p>
 * 适用于云厂商动态鉴权签名（如阿里云 IoT、腾讯云、华为云动态 HMAC Token）、
 * JWT 短期令牌或 STS 凭据在初次建连及断线自动重连时实时签发与刷新。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@FunctionalInterface
public interface MqttCredentialsProvider {

    /**
     * 动态解析指定命名客户端的认证凭据
     *
     * @param clientName       客户端逻辑名称
     * @param clientProperties 当前客户端配置属性
     * @param reconnect        是否为断线重连触发的凭据刷新（{@code false} 表示初次建连）
     * @return 解析后的认证凭据；返回 {@code null} 时保持或回退静态配置
     */
    MqttCredentials resolveCredentials(String clientName, MqttClientProperties clientProperties, boolean reconnect);
}
