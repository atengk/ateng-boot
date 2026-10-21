package io.github.atengk.boot.http.registrar.fixture;

import io.github.atengk.boot.http.annotation.HttpClient;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 派生元注解测试用自定义 HTTP 注解
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@HttpClient(name = "custom-service", url = "http://localhost:8081")
public @interface CustomClient {
}
