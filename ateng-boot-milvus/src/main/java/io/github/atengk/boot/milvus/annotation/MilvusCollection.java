package io.github.atengk.boot.milvus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体类对应的 Milvus 集合名称与基本属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusCollection {

    /**
     * 集合名称 (为空时默认采用类名下划线小写格式，如 UserVector -> user_vector)
     */
    String name() default "";

    /**
     * 集合语义描述信息
     */
    String description() default "";

    /**
     * 是否开启动态字段 ($meta)，开启后允许写入 Schema 外的任意 JSON 属性
     */
    boolean enableDynamicField() default true;

    /**
     * 分片数量 (默认为 1)
     */
    int numShards() default 1;

    /**
     * 集合数据物理存活时间 (秒，0 表示永不过期；大于 0 时自动在底层注入 collection.ttl.seconds)
     */
    long ttlSeconds() default 0;

    /**
     * 是否启用 MMap 内存映射存储 (开启后将标量与原始向量数据交换至本地磁盘，以降低物理内存成本)
     */
    boolean mmap() default false;

    /**
     * 集合加载至内存的服务副本数量 (默认 1，多副本可线性提升只读 QPS 并实现高可用负载均衡)
     */
    int replicaNumber() default 1;
}
