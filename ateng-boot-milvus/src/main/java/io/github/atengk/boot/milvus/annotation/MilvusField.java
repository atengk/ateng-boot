package io.github.atengk.boot.milvus.annotation;

import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体中的普通标量字段属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusField {

    /**
     * 自定义列字段名 (为空时取属性名)
     */
    String name() default "";

    /**
     * 显式指定 Milvus 数据类型 (默认 None 时由字段 Java 类型自适应推导)
     */
    DataType dataType() default DataType.None;

    /**
     * 当类型为 VarChar / 字符串类型时的最大字符长度限制，默认 65535
     */
    int maxLength() default 65535;

    /**
     * 字段语义描述信息
     */
    String description() default "";

    /**
     * 标量索引算法类型 (默认为 None 不建立索引；支持 INVERTED 倒排索引、TRIE 前缀树索引、STL_SORT 标量排序索引等)
     */
    IndexParam.IndexType indexType() default IndexParam.IndexType.None;

    /**
     * 自定义标量索引名称 (为空时由框架按 "idx_${fieldName}" 自动命名)
     */
    String indexName() default "";
}
