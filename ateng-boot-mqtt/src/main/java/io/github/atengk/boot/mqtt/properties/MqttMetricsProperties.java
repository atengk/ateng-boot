package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT Micrometer 度量采集基数治理配置属性
 * <p>
 * 默认屏蔽客户端通用收发与请求-响应超时指标的物理主题 {@code topic} 标签，
 * 防止百万级设备动态主题（如 {@code device/{sn}/data}）导致 Prometheus 时间序列基数爆炸引发 OOM。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class MqttMetricsProperties {

    /**
     * 是否在客户端通用发信、收信及请求-响应超时指标中附带物理主题 {@code topic} 标签（默认关闭以防御高基数 OOM）
     */
    private boolean includeTopicTag = false;

    /**
     * 当开启 {@link #includeTopicTag} 时允许登记的不同 {@code topic} 标签基数上限；超出部分统一归并至 {@code __overflow__}
     */
    private int maxTopicCardinality = 200;

    public boolean isIncludeTopicTag() {
        return includeTopicTag;
    }

    public void setIncludeTopicTag(boolean includeTopicTag) {
        this.includeTopicTag = includeTopicTag;
    }

    public int getMaxTopicCardinality() {
        return maxTopicCardinality;
    }

    public void setMaxTopicCardinality(int maxTopicCardinality) {
        this.maxTopicCardinality = Math.max(0, maxTopicCardinality);
    }
}
