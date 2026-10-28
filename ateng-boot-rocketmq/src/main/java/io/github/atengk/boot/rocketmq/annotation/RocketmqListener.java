package io.github.atengk.boot.rocketmq.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RocketMQ 5.x 声明式消息监听器注解
 *
 * <p>标注在 Spring 受管 Bean 的消费处理方法上，声明监听的主题 (Topic)、消费者组 (ConsumerGroup)、
 * Tag 标签过滤表达式或 SQL92 属性过滤表达式。
 * 容器启动后将自动注册底层受管 {@link org.apache.rocketmq.client.apis.consumer.PushConsumer} 实例，
 * 并在消费链路上内聚四维上下文激活、微服务推断反序列化、泛型集合解析、毒丸异常受控丢弃及防重幂等静默确认。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RocketmqListener {

    /**
     * 监听的 RocketMQ Topic 主题名称
     * 支持 Spring 属性占位符表达式，例如 "${ateng.mq.order-topic:order_topic}"
     *
     * @return 主题名称或占位符
     */
    String topic();

    /**
     * 绑定的消费者组 ConsumerGroup
     * 支持 Spring 属性占位符表达式，例如 "${ateng.mq.consumer-group:order_group}"
     *
     * @return 消费者组名称或占位符
     */
    String consumerGroup();

    /**
     * 服务端 Tag 过滤表达式（默认 "*" 订阅全部）
     * 支持多 Tag 逻辑或，例如 "tagA || tagB"
     *
     * @return Tag 过滤表达式
     */
    String tag() default "*";

    /**
     * 服务端 SQL92 属性过滤表达式（默认空）
     * 若配置该属性，则优先升级为 SQL92 过滤模式，忽略 tag 配置
     *
     * @return SQL92 过滤表达式
     */
    String sql92() default "";

    /**
     * 消费并发线程数（默认 0，表示采用全局配置 ateng.boot.rocketmq.consumer.consumption-thread-count）
     *
     * @return 并发消费线程数
     */
    int consumptionThreadCount() default 0;

    /**
     * 客户端本地最大缓存消息数量（默认 0，表示采用全局配置 ateng.boot.rocketmq.consumer.max-cache-message-count）
     *
     * @return 本地缓存消息最大数量
     */
    int maxCacheMessageCount() default 0;
}
