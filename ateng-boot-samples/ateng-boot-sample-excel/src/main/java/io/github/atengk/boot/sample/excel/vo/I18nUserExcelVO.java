package io.github.atengk.boot.sample.excel.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 示例多语言国际化 Excel 导入导出视图对象
 * 演示表头占位符 {i18n:xxx} 配合 Spring MessageSource 动态转译
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class I18nUserExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "{i18n:user.id}", index = 0)
    private Long id;

    @ExcelProperty(value = "{i18n:user.name}", index = 1)
    private String name;

    @ExcelProperty(value = "{i18n:user.email}", index = 2)
    private String email;

    @ExcelProperty(value = "{i18n:user.department:默认部门}", index = 3)
    private String department;
}
