package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个受管 MQTT 客户端句柄实体
 * 提供 Paho 异步客户端生命周期承载、防竞争单主题串行订阅门面与协议版本自省
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttClientHandle {

    private final String clientName;
    private final String actualClientId;
    private final MqttAsyncClient asyncClient;
    private final MqttConnectionOptions connectionOptions;
    private final MqttClientProperties clientProperties;
    private final String responseTopic;

    public MqttClientHandle(String clientName,
                            String actualClientId,
                            MqttAsyncClient asyncClient,
                            MqttConnectionOptions connectionOptions,
                            MqttClientProperties clientProperties) {
        this.clientName = clientName;
        this.actualClientId = actualClientId;
        this.asyncClient = asyncClient;
        this.connectionOptions = connectionOptions;
        this.clientProperties = clientProperties;
        this.responseTopic = clientProperties != null ? clientProperties.resolveResponseTopic(actualClientId) : "sys/response/" + actualClientId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getActualClientId() {
        return actualClientId;
    }

    public MqttAsyncClient getAsyncClient() {
        return asyncClient;
    }

    public MqttConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }

    public MqttClientProperties getClientProperties() {
        return clientProperties;
    }

    public String getResponseTopic() {
        return responseTopic;
    }

    public boolean isConnected() {
        return asyncClient != null && asyncClient.isConnected();
    }

    /**
     * 判断当前客户端是否为 MQTT 5.0 协议连接
     *
     * @return true 若为 MQTT 5.0
     */
    public boolean isMqttV5() {
        if (clientProperties != null && StringUtils.hasText(clientProperties.getProtocolVersion())) {
            String version = clientProperties.getProtocolVersion().trim();
            if ("3.1.1".equalsIgnoreCase(version) || "3.1".equalsIgnoreCase(version)) {
                return false;
            }
            if ("5.0".equalsIgnoreCase(version) || "5".equalsIgnoreCase(version)) {
                return true;
            }
        }
        if (connectionOptions != null) {
            return connectionOptions.getMqttVersion() == 5;
        }
        return true;
    }

    /**
     * 订阅单个主题并挂载消息监听器
     * 底层采用单元素数组封装提交，彻底规避 Paho 1.2.5 内部 subscriptionIdentifiers 空指针及并发竞态 Bug
     *
     * @param topicFilter 主题过滤器
     * @param qos         QoS 等级
     * @param listener    消息监听器（可选）
     * @return 订阅操作完成 Token
     * @throws MqttException 底层订阅异常
     */
    public IMqttToken subscribe(String topicFilter, int qos, IMqttMessageListener listener) throws MqttException {
        Assert.hasText(topicFilter, "topicFilter must not be blank");
        MqttSubscription subscription = new MqttSubscription(topicFilter, qos);
        IMqttMessageListener[] listeners = listener != null ? new IMqttMessageListener[]{listener} : null;
        IMqttToken token = asyncClient.subscribe(
                new MqttSubscription[]{subscription},
                null,
                null,
                listeners,
                null
        );
        long timeoutMs = clientProperties != null && clientProperties.getConnectionTimeout() != null
                ? clientProperties.getConnectionTimeout().toMillis() : 5000L;
        token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
        return token;
    }

    /**
     * 订阅单个主题
     *
     * @param topicFilter 主题过滤器
     * @param qos         QoS 等级
     * @return 订阅操作完成 Token
     * @throws MqttException 底层订阅异常
     */
    public IMqttToken subscribe(String topicFilter, int qos) throws MqttException {
        return subscribe(topicFilter, qos, null);
    }

    /**
     * 批量主题订阅防卫门面
     * 强制将批量主题数组解构为单主题串行迭代订阅，彻底防御 Paho 内部数组订阅并发锁竞争 Bug
     *
     * @param topicFilters 主题过滤器数组
     * @param qos          对应 QoS 等级数组
     * @throws MqttException 底层订阅异常
     */
    public void subscribeBatch(String[] topicFilters, int[] qos) throws MqttException {
        if (topicFilters == null || topicFilters.length == 0) {
            return;
        }
        if (qos == null || qos.length != topicFilters.length) {
            throw new IllegalArgumentException("QoS array length must match topicFilters length");
        }
        for (int i = 0; i < topicFilters.length; i++) {
            subscribe(topicFilters[i], qos[i]);
        }
    }

    /**
     * 退订单个或多个主题（串行迭代规避竞态）
     *
     * @param topicFilters 主题列表
     * @throws MqttException 底层退订异常
     */
    public void unsubscribe(String... topicFilters) throws MqttException {
        if (topicFilters == null || topicFilters.length == 0) {
            return;
        }
        long timeoutMs = clientProperties != null && clientProperties.getConnectionTimeout() != null
                ? clientProperties.getConnectionTimeout().toMillis() : 5000L;
        for (String topic : topicFilters) {
            if (StringUtils.hasText(topic)) {
                IMqttToken token = asyncClient.unsubscribe(topic);
                token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
            }
        }
    }

    /**
     * 获取客户端运行健康快照详情
     *
     * @return 包含连接状态、实际 ClientId、服务地址、协议版本、重连及缓冲积压信息的 Map
     */
    public Map<String, Object> getHealthDetails() {
        Map<String, Object> details = new HashMap<>();
        boolean connected = isConnected();
        details.put("status", connected ? "CONNECTED" : "DISCONNECTED");
        details.put("connected", connected);
        details.put("clientId", actualClientId);
        if (clientProperties != null) {
            details.put("serverUri", clientProperties.getServerUri());
            details.put("automaticReconnect", clientProperties.isAutomaticReconnect());
            details.put("protocolVersion", clientProperties.getProtocolVersion());
        }
        try {
            details.put("bufferedMessages", asyncClient != null ? asyncClient.getBufferedMessageCount() : 0);
        } catch (Exception ex) {
            details.put("bufferedMessages", -1);
        }
        return details;
    }
}
