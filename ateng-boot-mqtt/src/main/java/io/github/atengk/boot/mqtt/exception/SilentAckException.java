package io.github.atengk.boot.mqtt.exception;

/**
 * MQTT 静默确认异常 (Silent ACK Exception)
 * 当消费端在业务层检测到重复消费、业务幂等冲突或需直接阻断 QoS 1 重发风暴时抛出此异常
 * 底座捕获此异常后将直接向 Broker 提交安全 ACK，而不会转入死信主题或触发二次重投递
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class SilentAckException extends RuntimeException {

    public SilentAckException(String message) {
        super(message);
    }

    public SilentAckException(String message, Throwable cause) {
        super(message, cause);
    }
}
