package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 单个受管 MQTT 客户端句柄实体
 * 提供 Paho 异步客户端生命周期承载、防竞争单主题串行订阅门面与协议版本自省
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttClientHandle {

    private static final Logger log = LoggerFactory.getLogger(MqttClientHandle.class);

    private final String clientName;
    private final String actualClientId;
    private final MqttAsyncClient asyncClient;
    private final MqttConnectionOptions connectionOptions;
    private final MqttClientProperties clientProperties;
    private final String responseTopic;
    private volatile IMqttMessageListener fallbackMessageListener;
    private final List<BiConsumer<Boolean, String>> connectCompleteListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disconnectedPublished = new AtomicBoolean(false);
    private volatile boolean closing = false;

    public void markClosing() {
        this.closing = true;
    }

    public boolean isClosing() {
        return closing;
    }

    public boolean markDisconnected() {
        return disconnectedPublished.compareAndSet(false, true);
    }

    public void resetDisconnected() {
        disconnectedPublished.set(false);
    }

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

    public IMqttMessageListener getFallbackMessageListener() {
        return fallbackMessageListener;
    }

    public void setFallbackMessageListener(IMqttMessageListener fallbackMessageListener) {
        this.fallbackMessageListener = fallbackMessageListener;
    }

    public void addConnectCompleteListener(BiConsumer<Boolean, String> listener) {
        if (listener != null) {
            this.connectCompleteListeners.add(listener);
        }
    }

    public void notifyConnectComplete(boolean reconnect, String serverURI) {
        for (BiConsumer<Boolean, String> listener : connectCompleteListeners) {
            try {
                listener.accept(reconnect, serverURI);
            } catch (Throwable ignored) {
            }
        }
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
        return subscribe(topicFilter, qos, false, false, 0, listener);
    }

    /**
     * 订阅单个主题并挂载消息监听器（支持完整 MQTT 5.0 订阅选项）
     * 底层采用单元素数组封装提交，彻底规避 Paho 1.2.5 内部 subscriptionIdentifiers 空指针及并发竞态 Bug
     *
     * @param topicFilter       主题过滤器
     * @param qos               QoS 等级
     * @param noLocal           禁止接收自身连接发出的消息
     * @param retainAsPublished 转发时保留发布端原始 Retain 标志
     * @param retainHandling    订阅建立时保留消息投递策略 (0, 1, 2)
     * @param listener          消息监听器（可选）
     * @return 订阅操作完成 Token
     * @throws MqttException 底层订阅异常
     */
    public IMqttToken subscribe(String topicFilter,
                                int qos,
                                boolean noLocal,
                                boolean retainAsPublished,
                                int retainHandling,
                                IMqttMessageListener listener) throws MqttException {
        Assert.hasText(topicFilter, "topicFilter must not be blank");
        MqttSubscription subscription = new MqttSubscription(topicFilter, qos);
        subscription.setNoLocal(noLocal);
        subscription.setRetainAsPublished(retainAsPublished);
        subscription.setRetainHandling(retainHandling);
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
     * 在开启 Paho manualAcks 模式下统一提交入站消息确认 (QoS 1/2 提交 PUBACK/PUBCOMP，QoS 0 自动跳过)
     *
     * @param message 已接收的 MQTT 报文
     */
    public void acknowledgeMessage(MqttMessage message) {
        if (message == null || message.getQos() <= 0 || asyncClient == null || !asyncClient.isConnected()) {
            return;
        }
        try {
            asyncClient.messageArrivedComplete(message.getId(), message.getQos());
        } catch (Exception e) {
            log.warn("[MQTT] Failed to acknowledge message [id={}, qos={}] on client [{}]: {}",
                    message.getId(), message.getQos(), clientName, e.getMessage());
        }
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
                try {
                    IMqttToken token = asyncClient.unsubscribe(topic);
                    token.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
                } catch (MqttException e) {
                    // 防御 Paho 内部 CommsCallback 遍历回调时的瞬态 ConcurrentModificationException 竞态
                    if (e.getCause() instanceof ConcurrentModificationException || (e.getMessage() != null && e.getMessage().contains("ConcurrentModificationException"))) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        IMqttToken retryToken = asyncClient.unsubscribe(topic);
                        retryToken.waitForCompletion(timeoutMs > 0 ? timeoutMs : 5000L);
                    } else {
                        throw e;
                    }
                }
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
            details.put("serverUri", clientProperties.resolvePrimaryServerUri());
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
