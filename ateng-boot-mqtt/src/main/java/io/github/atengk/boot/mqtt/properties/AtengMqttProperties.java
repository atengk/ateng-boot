package io.github.atengk.boot.mqtt.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ateng-boot-mqtt 全局配置属性类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@ConfigurationProperties(prefix = "ateng.boot.mqtt")
public class AtengMqttProperties {

    /**
     * 全局 MQTT Starter 总开关
     */
    private boolean enabled = true;

    /**
     * 默认生效的主客户端逻辑名称
     */
    private String defaultClient = "primary";

    /**
     * 命名多客户端配置注册表（支持多 Broker 或多园区隔离）
     */
    @NestedConfigurationProperty
    private Map<String, MqttClientProperties> clients = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultClient() {
        return defaultClient;
    }

    public void setDefaultClient(String defaultClient) {
        this.defaultClient = defaultClient;
    }

    public Map<String, MqttClientProperties> getClients() {
        return clients;
    }

    public void setClients(Map<String, MqttClientProperties> clients) {
        this.clients = clients;
    }
}
