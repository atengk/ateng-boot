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
     * 死信受控重放最大重试上限（默认 1 次，防止毒丸报文在业务主题与死信主题之间无限死循环）
     */
    private int maxReplayCount = 1;

    /**
     * Micrometer 度量采集低基数治理配置
     */
    @NestedConfigurationProperty
    private MqttMetricsProperties metrics = new MqttMetricsProperties();

    /**
     * 消费端确认模式与退避重试策略配置
     */
    @NestedConfigurationProperty
    private MqttConsumerProperties consumer = new MqttConsumerProperties();

    /**
     * 死信投递与本地死信存储策略配置
     */
    @NestedConfigurationProperty
    private MqttDltProperties dlt = new MqttDltProperties();

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

    public int getMaxReplayCount() {
        return maxReplayCount;
    }

    public void setMaxReplayCount(int maxReplayCount) {
        this.maxReplayCount = Math.max(1, maxReplayCount);
    }

    public MqttMetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MqttMetricsProperties metrics) {
        this.metrics = metrics != null ? metrics : new MqttMetricsProperties();
    }

    public MqttConsumerProperties getConsumer() {
        return consumer;
    }

    public void setConsumer(MqttConsumerProperties consumer) {
        this.consumer = consumer != null ? consumer : new MqttConsumerProperties();
    }

    public MqttDltProperties getDlt() {
        return dlt;
    }

    public void setDlt(MqttDltProperties dlt) {
        this.dlt = dlt != null ? dlt : new MqttDltProperties();
    }

    public Map<String, MqttClientProperties> getClients() {
        return clients;
    }

    public void setClients(Map<String, MqttClientProperties> clients) {
        this.clients = clients;
    }
}
