package io.github.atengk.boot.milvus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体中的物理分区键字段 (专为 SaaS 多租户或分类隔离设计)
 * Milvus 服务端自动根据该字段哈希将记录物理路由至专属分区以实现毫秒级过滤加速
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusPartitionKey {

    /**
     * 自定义分区键字段名 (为空时取属性名)
     */
    String name() default "";

    /**
     * 分区键为字符串类型时的最大长度限制，默认 128
     */
    int maxLength() default 128;

    /**
     * 字段语义描述
     */
    String description() default "多租户/分类分区路由键";
}
