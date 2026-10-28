package io.github.atengk.boot.mqtt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MQTT 主题路径占位符变量注入注解
 * 从带有 REST 风格占位符的主题模式（如 {@code device/{deviceId}/telemetry}）中提取实际变量并注入方法形参
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqttVariable {

    /**
     * 绑定的主题路径占位符变量名称
     */
    String value();
}
