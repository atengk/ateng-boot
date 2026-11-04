package io.github.atengk.boot.milvus.annotation;

import io.milvus.v2.common.IndexParam;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体中的向量特征字段 (支持 List&lt;Float&gt;, float[], ByteBuffer 等)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusVector {

    /**
     * 自定义向量字段名 (为空时取属性名)
     */
    String name() default "";

    /**
     * 向量特征维度 (如 OpenAI text-embedding-3-small 为 1536，bge-m3 为 1024)
     */
    int dimension();

    /**
     * 默认距离度量类型 (默认 COSINE 余弦相似度，推荐文本语义检索)
     */
    IndexParam.MetricType metricType() default IndexParam.MetricType.COSINE;

    /**
     * 默认向量索引算法类型 (默认 AUTOINDEX，由服务端自适应选择最优算法如 HNSW/FLAT)
     */
    IndexParam.IndexType indexType() default IndexParam.IndexType.AUTOINDEX;

    /**
     * 向量字段描述信息
     */
    String description() default "特征向量字段";
}
