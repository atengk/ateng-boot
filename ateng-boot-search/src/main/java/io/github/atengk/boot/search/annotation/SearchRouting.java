package io.github.atengk.boot.search.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 搜索引擎分片自定义路由键标识注解
 * <p>
 * 标注于实体属性上，声明物理分片路由键（Routing Key），实现单分片定向命中与全生命周期防漏校验。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SearchRouting {
}
