package io.github.atengk.boot.mybatisplus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 SQL 诊断与审计日志忽略注解
 * 可标注在 Mapper 接口类或特定方法上，指示 {@code SqlLogInterceptor}
 * 对目标 SQL 语句跳过控制台诊断输出与外部 SPI 存储持久化派发。
 * 适用于心跳健康探针、高频调度轮询表等无业务审计价值的场景。
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IgnoreSqlLog {

    /**
     * 忽略原因或业务说明（便于代码审阅与维护追溯）
     *
     * @return 业务说明
     */
    String value() default "";
}
