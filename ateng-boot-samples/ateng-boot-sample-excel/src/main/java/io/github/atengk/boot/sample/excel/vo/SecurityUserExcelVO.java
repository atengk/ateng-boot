package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import io.github.atengk.boot.excel.annotation.ExcelFormulaAllowed;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 示例安全与防呆锁定 Excel 导入导出视图对象
 * 演示公式注入安全防御、@ExcelFormulaAllowed 白名单豁免与 @ExcelColumnLock 列级防呆只读保护
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecurityUserExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工工号（系统生成，列级锁定禁止用户编辑）
     */
    @ExcelColumnLock
    @ExcelProperty(value = "系统工号", index = 0)
    private Long employeeNo;

    /**
     * 员工姓名（公式注入高危字段，例如 "=cmd|'/C calc'!A0" 会被安全转译）
     */
    @ExcelProperty(value = "员工姓名", index = 1)
    private String employeeName;

    /**
     * 薪酬计算公式（合法计算公式，通过 @ExcelFormulaAllowed 白名单豁免）
     */
    @ExcelFormulaAllowed
    @ExcelProperty(value = "薪资计算公式", index = 2)
    private String salaryFormula;

    /**
     * 部门职级（只读锁定）
     */
    @ExcelColumnLock
    @ExcelProperty(value = "核定职级", index = 3)
    private String grade;
}
