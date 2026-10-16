package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 相邻行单元格纵向动态合并注解
 * <p>
 * 标注于导出模型类属性上，当相邻行该字段的值相同时，导出时自动将纵向单元格合并为一个合并单元格。
 * 支持通过 {@link #dependParent()} 绑定左侧主列，防止不同上级分类下的同名子项发生跨层级合并。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelMerge {

    /**
     * 是否启用纵向自动合并
     *
     * @return 默认 {@code true}
     */
    boolean value() default true;

    /**
     * 是否依赖前序（左侧）合并列的边界
     * <p>
     * 为 {@code true} 时，仅当左侧主列在同一个合并区间内且当前列值相同时才进行合并。
     * </p>
     *
     * @return 默认 {@code false}（各列独立合并）
     */
    boolean dependParent() default false;
}
