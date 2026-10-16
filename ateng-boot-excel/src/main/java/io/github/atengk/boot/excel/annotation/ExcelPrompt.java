package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.*;

/**
 * Excel 导入模板输入提示与表头批注注解
 * <p>
 * 标注于导入实体字段上，在生成 Excel 模板时自动在表头单元格嵌入悬浮批注，
 * 并在数据单元格悬浮展示输入规范提示框，指导用户规范录入。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelPrompt {

    /**
     * 提示详细内容（例如：{@code "必填，11位数字手机号"}）
     */
    String value();

    /**
     * 提示标题（默认为：{@code "填写说明"}）
     */
    String title() default "填写说明";
}
