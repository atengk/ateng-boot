package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.*;

/**
 * Excel 导入模板下拉选择约束注解
 * <p>
 * 标注于导入实体字段上，在生成 Excel 导入模板时，该列自动生成 Excel 原生下拉选择框，
 * 严格限制用户的可选输入值，防止手工敲错。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelDropdown {

    /**
     * 静态下拉候选选项列表（例如：{@code {"男", "女"}}）
     */
    String[] value() default {};

    /**
     * 动态下拉选项提供者 Class（支持从数据库字典或枚举中动态提取）
     */
    Class<? extends DropdownProvider> source() default DropdownProvider.Default.class;

    /**
     * 下拉约束生效起始行（默认从第 2 行开始，即跳过表头从首行数据生效）
     */
    int firstRow() default 1;

    /**
     * 下拉约束生效终止行（默认到第 65535 行）
     */
    int lastRow() default 65535;
}
