package io.github.atengk.boot.core.annotation;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.jackson.SensitiveJsonSerializer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 视图层字段动态脱敏注解
 * 基于 Jackson 序列化机制，仅在 Controller 将实体或 VO 序列化为 JSON 响应时动态掩码
 * 内存中 Java 实体对象与底层存储始终保持真实原始数据，恪守 ADR-0012 内存真明文原则
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@JacksonAnnotationsInside
@JsonSerialize(using = SensitiveJsonSerializer.class)
public @interface FieldSensitive {

    /**
     * 脱敏规则类型
     *
     * @return 预置脱敏类型，默认为自定义 CUSTOM
     */
    SensitiveType value() default SensitiveType.CUSTOM;

    /**
     * 自定义掩码起始索引（包含，从 0 开始，仅在 value 为 CUSTOM 时生效）
     *
     * @return 起始索引
     */
    int startInclude() default 0;

    /**
     * 自定义掩码结束索引（不包含，仅在 value 为 CUSTOM 时生效）
     *
     * @return 结束索引
     */
    int endExclude() default 0;

    /**
     * 自定义头部保留字符数（仅在 value 为 KEEP_HEAD_TAIL 时生效）
     *
     * @return 头部保留字符数
     */
    int keepHead() default 0;

    /**
     * 自定义尾部保留字符数（仅在 value 为 KEEP_HEAD_TAIL 时生效）
     *
     * @return 尾部保留字符数
     */
    int keepTail() default 0;

    /**
     * 自定义替换掩码字符，默认为 '*'
     *
     * @return 掩码字符
     */
    char maskChar() default '*';
}
