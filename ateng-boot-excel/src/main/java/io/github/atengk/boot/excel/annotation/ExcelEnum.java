package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 枚举双向转义注解
 * <p>
 * 标注于枚举或基础类型字段上，在导出时自动将枚举或底层编码转换为中文文本，
 * 在导入时将中文文本反查解析为枚举实例或底层编码。
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelEnum {

    /**
     * 目标枚举类
     * <p>
     * 若字段类型本身就是枚举类型，可保持默认值（由框架自动推断）。
     * 若字段类型为 String、Integer 等编码类型，需显式指定对应的枚举 Class。
     *
     * @return 目标枚举 Class
     */
    Class<? extends Enum<?>> value() default VoidEnum.class;

    /**
     * 枚举编码属性名或方法名
     * <p>
     * 默认按 {@code "code"} 查找对应 getter 或属性；若未匹配则尝试 {@code getValue()}，
     * 最终回退至 {@link Enum#name()}。
     *
     * @return 编码属性名
     */
    String codeField() default "code";

    /**
     * 枚举中文描述/文本属性名或方法名
     * <p>
     * 默认按 {@code "text"} 查找对应 getter 或属性；若未匹配则依次尝试
     * {@code "desc"}、{@code "label"}、{@code "message"}，最终回退至 {@link Enum#name()}。
     *
     * @return 文本属性名
     */
    String textField() default "text";

    /**
     * 当导入或导出未匹配到枚举项时，是否忽略并保持原值或 null
     * <p>
     * 为 {@code true} 时未匹配返回原值或 null；为 {@code false} 时抛出转换异常。
     *
     * @return 是否忽略未匹配项
     */
    boolean ignoreUnmapped() default true;

    /**
     * 哨兵类型，用于默认值缺省判断
     */
    enum VoidEnum {}
}
