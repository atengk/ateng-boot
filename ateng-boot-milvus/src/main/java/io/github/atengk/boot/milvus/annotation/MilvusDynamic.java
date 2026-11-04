package io.github.atengk.boot.milvus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注实体中承载 Milvus 动态字段 ($meta) 的扩展字典属性
 *
 * <p>被该注解修饰的属性类型必须为 {@link java.util.Map}，在查询反序列化时自动收集未显式定义的动态列，
 * 写入时自动将其扁平化写入 Milvus 动态列字典中，防止非结构化扩展数据丢失。</p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusDynamic {
}
