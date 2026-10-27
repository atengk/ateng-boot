package io.github.atengk.boot.redis.stream.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 Redis Stream 消费组监听注解
 * 声明于 Spring Bean 方法上，由 StreamBootstrap 容器拉起受管多线程并发消费
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisStreamListener {

    /**
     * Stream 键名（支持 ${property} 占位符解析）
     */
    String stream() default "";

    /**
     * 消费组名称（支持 ${property} 占位符解析）
     */
    String group() default "";

    /**
     * 消费者并发线程数（默认 1）
     */
    int concurrency() default 1;

    /**
     * 业务方法正常返回后是否自动提交确认 (XACK)（默认 true）
     */
    boolean autoAck() default true;

    /**
     * 单次拉取最大批量数（小于等于 0 时继承全局配置）
     */
    int batchSize() default -1;

    /**
     * 独立失败重试次数上限（小于 0 时继承全局配置）
     */
    int retryTimes() default -1;
}
