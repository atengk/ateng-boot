package io.github.atengk.boot.redis.pubsub.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis Pub/Sub 广播主题声明式监听注解
 * 标注于 Spring 受管 Bean 的方法上，由 {@code ChannelTopicBootstrap} 容器自动完成主题订阅与消息分发
 *
 * <p>方法签名支持如下形参定义：
 * <ul>
 *   <li>1 个参数：{@code onMessage(T payload)}（自动根据泛型反序列化为目标对象，或为 String / byte[] / Message）</li>
 *   <li>2 个参数：{@code onMessage(T payload, String channel)}</li>
 *   <li>3 个参数：{@code onMessage(T payload, String channel, String pattern)}</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ChannelTopicListener {

    /**
     * 待订阅的精确主题名称（与 {@link #topics()} 等价），支持 {@code ${property}} 占位符
     *
     * @return 广播主题列表
     */
    @AliasFor("topics")
    String[] value() default {};

    /**
     * 待订阅的精确主题名称列表，支持 {@code ${property}} 占位符
     *
     * @return 广播主题列表
     */
    @AliasFor("value")
    String[] topics() default {};

    /**
     * 待订阅的单个精确主题名称别名，支持 {@code ${property}} 占位符
     *
     * @return 广播主题
     */
    String topic() default "";

    /**
     * 待订阅的模式通配主题列表（如 {@code topic:*})，支持 {@code ${property}} 占位符
     *
     * @return 广播通配模式列表
     */
    String[] patterns() default {};

    /**
     * 当前监听器的处理并发度（调度线程数），默认 1
     *
     * @return 并发处理线程数
     */
    int concurrency() default 1;
}
