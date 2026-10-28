package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 合法公式白名单豁免注解
 * <p>
 * 标注于导出实体字段上。默认情况下，框架对以 {@code =}, {@code +}, {@code -}, {@code @}, {@code \t}, {@code \r}
 * 开头的潜在公式字符串自动前置单引号 {@code '} 转译为普通文本以防御 CSV/Excel 公式注入攻击 (CWE-1236)。
 * 当字段显式标注本注解且 {@code value = true} 时，豁免转译防御，允许单元格以真实公式或原始格式导出至电子表格。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelFormulaAllowed {

    /**
     * 是否允许公式执行（默认为 true）
     *
     * @return true 表示白名单豁免，允许作为公式导出；false 表示依然进行安全转译防御
     */
    boolean value() default true;
}
