package io.github.atengk.boot.mqtt.properties;

import java.time.Duration;

/**
 * MQTT 消费端确认模式与受管退避重试策略配置属性
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class MqttConsumerProperties {

    /**
     * 全局消费确认模式（默认 AUTO_AFTER_CONSUME，可设为 MANUAL 或在 @MqttListener 端点级覆盖）
     */
    private AckMode ackMode = AckMode.AUTO_AFTER_CONSUME;

    /**
     * 遇可恢复非致命异常时的最大重试次数（不含首次执行，默认 2 次）
     */
    private int maxRetries = 2;

    /**
     * 首次退避重试等待间隔（默认 200ms）
     */
    private Duration initialBackoff = Duration.ofMillis(200);

    /**
     * 指数退避乘数因子（默认 2.0）
     */
    private double backoffMultiplier = 2.0;

    /**
     * 最大退避重试等待上限（默认 5s）
     */
    private Duration maxBackoff = Duration.ofSeconds(5);

    public AckMode getAckMode() {
        return ackMode;
    }

    public void setAckMode(AckMode ackMode) {
        this.ackMode = ackMode != null ? ackMode : AckMode.AUTO_AFTER_CONSUME;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff != null && !initialBackoff.isNegative()
                ? initialBackoff : Duration.ofMillis(200);
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier >= 1.0 ? backoffMultiplier : 1.0;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff != null && !maxBackoff.isNegative()
                ? maxBackoff : Duration.ofSeconds(5);
    }
}
