package io.github.atengk.boot.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 列级防呆只读锁定注解
 * <p>
 * 标注于导出实体字段或类上。结合 {@code ColumnLockSheetWriteHandler} 使用，
 * 在导出或生成模板时对指定列开启单元格锁定（{@code locked = true}）并自动开启工作表保护（{@code sheet.protectSheet(password)}），
 * 未标注或 {@code locked = false} 的列保持可编辑，实现导入模板防呆输入与数据防篡改。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelColumnLock {

    /**
     * 当前列是否开启只读锁定（默认为 true）
     *
     * @return true 表示只读锁定不可编辑；false 表示允许编辑
     */
    boolean locked() default true;

    /**
     * 工作表保护解锁密码（默认为空字符串，即保护但不设强密码）
     *
     * @return 解锁密码
     */
    String password() default "";
}
