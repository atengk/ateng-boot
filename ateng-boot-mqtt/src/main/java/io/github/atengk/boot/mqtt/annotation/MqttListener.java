package io.github.atengk.boot.mqtt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 MQTT 消息事件驱动监听器注解
 * 标注在 Spring 受管 Bean 的方法上，容器启动时自动建立主题订阅与受管异步调度通道
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqttListener {

    /**
     * 订阅的目标主题或主题模式列表
     * 支持通配符（单层 {@code +}、多层 {@code #}）及 REST 风格路径变量占位符（如 {@code device/{deviceId}/telemetry}）
     */
    String[] topics();

    /**
     * 订阅服务质量等级 (QoS 0, 1, 2)，默认为 QoS 1
     */
    int qos() default 1;

    /**
     * 绑定的命名 MQTT 客户端逻辑名称
     * 若未指定，则自动绑定系统默认客户端
     */
    String client() default "";

    /**
     * 共享订阅消费组名称（Shared Subscriptions）
     * 指定后底层自动转换为 {@code $share/<sharedGroup>/<topic>}，由 Broker 在集群多 Pod 间实施负载均衡消费
     */
    String sharedGroup() default "";

    /**
     * 监听器并发处理度提示（默认 1）
     */
    int concurrency() default 1;

    /**
     * 是否启用死信队列（DLT）转移机制（默认 true）
     * 当遇到反序列化崩溃、格式错误或不可自愈异常时，自动转移到死信主题并提交 ACK 阻断死循环
     */
    boolean enableDlt() default true;

    /**
     * 自定义死信目标主题（默认空，为空时自动派生为 ${topic}/DLT）
     */
    String dltTopic() default "";
}
