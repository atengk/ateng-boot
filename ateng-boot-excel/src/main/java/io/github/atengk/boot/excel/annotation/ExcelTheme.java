package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.*;

/**
 * Excel 导出模版主题注解
 * <p>
 * 标注在 Excel 导出实体类上，用于声明式指定当前导出所采用的企业级视觉主题、斑马纹交替底色
 * 及自适应列宽规则，具有高于全局配置的优先权。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ExcelTheme {

    /**
     * 是否启用样式体系（设为 false 可针对特定实体单次导出关闭样式）
     */
    boolean enabled() default true;

    /**
     * 快捷指定预设模版主题名称（如 "FRESH_GREEN"、"business-blue"，留空时以 theme() 为准）
     */
    String value() default "";

    /**
     * 预设模版主题（默认极简白）
     */
    io.github.atengk.boot.excel.enums.ExcelTheme theme() default io.github.atengk.boot.excel.enums.ExcelTheme.MINIMAL_CLEAN;

    /**
     * 是否启用正文奇偶行斑马纹交替渲染
     */
    boolean zebraStripe() default true;

    /**
     * 是否启用智能自适应列宽
     */
    boolean autoColumnWidth() default true;

    /**
     * 最小列宽字符数（默认 -1 表示继承全局配置）
     */
    int minColumnWidth() default -1;

    /**
     * 最大列宽字符数（默认 -1 表示继承全局配置）
     */
    int maxColumnWidth() default -1;

    /**
     * 字体名称（留空表示继承全局配置）
     */
    String fontName() default "";
}
