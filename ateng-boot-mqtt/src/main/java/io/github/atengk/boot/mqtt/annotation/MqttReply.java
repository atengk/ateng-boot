package io.github.atengk.boot.mqtt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 MQTT 响应端应答配置注解
 * <p>
 * 配合非 {@code void} 或 {@link java.util.concurrent.CompletableFuture} 返回值的
 * {@link MqttListener} 方法使用，用于定制应答 QoS、保留标志或缺省回发主题（当入站报文未携带 {@code ResponseTopic} 时启用兜底主题）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqttReply {

    /**
     * 兜底固定应答主题（当入站 MQTT 5.0 报文未携带 {@code ResponseTopic} 时使用）
     */
    String topic() default "";

    /**
     * 应答消息服务质量等级 (0, 1, 2)，默认 QoS 1
     */
    int qos() default 1;

    /**
     * 应答消息是否作为保留消息 (Retain)，默认 false
     */
    boolean retained() default false;
}
