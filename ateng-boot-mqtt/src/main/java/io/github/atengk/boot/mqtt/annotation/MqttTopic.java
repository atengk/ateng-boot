package io.github.atengk.boot.mqtt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MQTT 物理来源主题形参注入注解
 * 标注在监听器方法的 String 类型形参上，注入当前接收消息的实际物理主题
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqttTopic {
}
