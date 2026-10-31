package io.github.atengk.boot.mqtt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MQTT 用户属性标头形参注入注解
 * 从入站报文的 User Properties 或协议标头中提取指定键的值并注入方法形参
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqttHeader {

    /**
     * 绑定的 User Property 标头键名（如 {@code X-Trace-Id}）；当标注于 Map 且未指定 key 时注入全量标头字典
     */
    String value() default "";

    /**
     * 是否必须存在，默认为 false
     */
    boolean required() default false;
}
