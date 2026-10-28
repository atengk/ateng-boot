package io.github.atengk.boot.web.version.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 API 版本控制注解
 * 支持标注于 Controller 类或方法上，配合 Spring MVC 原生 RequestCondition 挂载 URL 路径版本前缀（/v{version}/）与请求头版本（X-API-Version）双向精准分流
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiVersion {

    /**
     * API 版本字符串标识（如 "1", "1.0", "2"）
     * 默认值为 "1"
     *
     * @return 字符串版本标识
     */
    String value() default "1";

    /**
     * API 主版本数值（如 1, 2）
     * 当需要显式声明数值主版本时使用（如 {@code @ApiVersion(number = 2)}）；
     * 若未提供非默认的 value，则优先以 number 数值生效
     *
     * @return 主版本数值
     */
    int number() default 0;
}
