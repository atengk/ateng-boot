package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 字典双向转义注解
 * <p>
 * 标注于字典编码字段（如 String、Integer 等），结合 {@code DictProvider} SPI，
 * 在导出时自动将编码转为可读中文标签，在导入时将中文标签反向翻译为编码。
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelDict {

    /**
     * 字典类型标识
     * <p>
     * 例如 {@code "sys_user_status"}、{@code "sys_user_gender"} 等。
     *
     * @return 字典类型编码
     */
    String value() default "";

    /**
     * 字典类型标识（与 {@link #value()} 互为别名）
     *
     * @return 字典类型编码
     */
    String type() default "";

    /**
     * 当字典提供者未查到匹配映射时，是否保留原值
     * <p>
     * 为 {@code true} 时保留原值；为 {@code false} 时输出或反查返回 {@code null}。
     *
     * @return 是否保留原值
     */
    boolean keepOriginalIfUnmapped() default true;
}
