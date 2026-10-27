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
     * API 版本标识（支持数字或语义化版本号，如 "1", "1.0", "2"）
     * 默认值为 "1"
     *
     * @return 版本标识
     */
    String value() default "1";
}
