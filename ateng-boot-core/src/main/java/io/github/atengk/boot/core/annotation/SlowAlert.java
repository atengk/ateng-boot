package io.github.atengk.boot.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级长耗时慢调用主动告警注解
 * 支持标注于类或方法上，当目标方法执行耗时超过配置阈值时输出结构化 WARN 日志
 * 默认记录入参快照并提供 1KB 智能截断防 OOM 与敏感凭据掩码
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SlowAlert {

    /**
     * 业务操作或方法语义描述（如 "批量生成报表"、"查询外部商户配置"）
     *
     * @return 业务操作描述
     */
    String value() default "";

    /**
     * 慢调用告警耗时阈值（毫秒），小于等于 0 时回退使用全局配置 ateng.boot.core.slow-alert.default-threshold（默认 500ms）
     *
     * @return 慢调用耗时阈值（毫秒）
     */
    long threshold() default 0;

    /**
     * 是否在告警日志中打印方法入参快照（默认 true）
     *
     * @return true 表示记录入参快照
     */
    boolean includeArgs() default true;

    /**
     * 入参快照最大字符截断长度（默认 1024 字符 = 1KB，防大报文引发 OOM）
     *
     * @return 最大截断长度
     */
    int maxArgsLength() default 1024;
}
