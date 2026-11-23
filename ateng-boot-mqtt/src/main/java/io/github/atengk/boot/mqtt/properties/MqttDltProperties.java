package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT 死信投递、本地死信存储与 Broker 兜底探测策略配置属性
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class MqttDltProperties {

    /**
     * 向 Broker DLT 主题转发死信报文时是否设置为 retained 保留消息（默认 false，避免单槽位覆盖丢失连续毒丸）
     */
    private boolean retained = false;

    /**
     * 当本地 DeadLetterStore 中指定死信主题为空时，是否允许回退订阅 Broker DLT 主题进行兜底探测（默认 true）
     */
    private boolean peekBrokerFallback = true;

    /**
     * 默认内存死信存储单个 DLT 主题最大保留条数（超出按 FIFO 淘汰最旧记录）
     */
    private int maxRecordsPerTopic = 1000;

    /**
     * 默认内存死信存储全局跨主题最大保留总条数（超出按 FIFO 淘汰最旧记录）
     */
    private int maxTotalRecords = 10000;

    public boolean isRetained() {
        return retained;
    }

    public void setRetained(boolean retained) {
        this.retained = retained;
    }

    public boolean isPeekBrokerFallback() {
        return peekBrokerFallback;
    }

    public void setPeekBrokerFallback(boolean peekBrokerFallback) {
        this.peekBrokerFallback = peekBrokerFallback;
    }

    public int getMaxRecordsPerTopic() {
        return maxRecordsPerTopic;
    }

    public void setMaxRecordsPerTopic(int maxRecordsPerTopic) {
        if (maxRecordsPerTopic <= 0) {
            throw new IllegalArgumentException("maxRecordsPerTopic must be positive: " + maxRecordsPerTopic);
        }
        this.maxRecordsPerTopic = maxRecordsPerTopic;
    }

    public int getMaxTotalRecords() {
        return maxTotalRecords;
    }

    public void setMaxTotalRecords(int maxTotalRecords) {
        if (maxTotalRecords <= 0) {
            throw new IllegalArgumentException("maxTotalRecords must be positive: " + maxTotalRecords);
        }
        this.maxTotalRecords = maxTotalRecords;
    }
}
