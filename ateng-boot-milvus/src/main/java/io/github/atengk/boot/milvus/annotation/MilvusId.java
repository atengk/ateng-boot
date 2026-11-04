package io.github.atengk.boot.milvus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体主键字段 (支持 Long/Int64 或 String/VarChar)
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MilvusId {

    /**
     * 自定义主键字段名 (为空时取属性名)
     */
    String name() default "";

    /**
     * 是否由 Milvus 服务端自动生成全局唯一自增 ID (仅限 Long/Int64 类型生效)
     */
    boolean autoId() default false;

    /**
     * 当主键为字符串 (VarChar) 类型时的最大长度，默认 64
     */
    int maxLength() default 64;

    /**
     * 主键字段描述
     */
    String description() default "主键ID";
}
