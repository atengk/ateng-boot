package io.github.atengk.boot.redis.delay.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 Redis 延时队列消费监听注解
 * 标注在 Spring 受管 Bean 的方法上，容器启动后自动拉起后台守护线程循环拉取消费
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DelayedQueueListener {

    /**
     * 延时队列名称（同 queue，支持 ${property:default} 占位符解析）
     *
     * @return 延时队列名称
     */
    @AliasFor("queue")
    String value() default "";

    /**
     * 延时队列名称（同 value，支持 ${property:default} 占位符解析）
     *
     * @return 延时队列名称
     */
    @AliasFor("value")
    String queue() default "";

    /**
     * 并发消费工作线程数（默认 1）
     *
     * @return 并发消费线程数
     */
    int concurrency() default 1;
}
