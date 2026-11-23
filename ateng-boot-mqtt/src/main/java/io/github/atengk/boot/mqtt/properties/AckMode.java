package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT 消费端消息确认模式枚举
 *
 * @author Ateng
 * @since 2026-11-18
 */
public enum AckMode {

    /**
     * 消费成功或重试/毒丸闭环处理完成后由框架自动提交确认（默认）
     */
    AUTO_AFTER_CONSUME,

    /**
     * 交由业务监听方法形参注入 {@link io.github.atengk.boot.mqtt.listener.MqttAcknowledgment} 显式手动控制确认
     */
    MANUAL
}
