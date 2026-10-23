package io.github.atengk.boot.notification.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式异常告警切面注解
 * 标注于 Controller、Service 或方法上，在抛出未受控异常时自动采集堆栈与全链路上下文并推送卡片告警
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface NotifyAlert {

    /**
     * 告警推送的目标渠道标识名称（未指定时回退至告警配置或全局默认渠道）
     */
    String channel() default "";

    /**
     * 告警卡片标题摘要
     */
    String title() default "系统未捕获异常告警";

    /**
     * 当前方法专有的防风暴聚合抑制时间窗口（单位：秒）
     * 默认 -1 表示沿用全局配置属性（默认 60 秒）
     */
    long suppressWindow() default -1;

    /**
     * 忽略不触发告警的异常类型列表（如特定的预期业务异常）
     */
    Class<? extends Throwable>[] ignoreExceptions() default {};

    /**
     * 仅在此类异常或其子类抛出时触发告警，默认所有 Throwable
     */
    Class<? extends Throwable>[] includeExceptions() default {Throwable.class};

    /**
     * 是否在告警卡片中包含经过脱敏的方法调用入参快照，默认开启
     */
    boolean includeArgs() default true;
}
