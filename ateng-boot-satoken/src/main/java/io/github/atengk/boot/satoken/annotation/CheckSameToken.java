package io.github.atengk.boot.satoken.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 微服务内部调用 Same-Token 零信任安全校验注解
 * 可标注于类或方法上；调用前将强制校验当前请求头中携带的 Same-Token 是否合法有效
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CheckSameToken {
}
