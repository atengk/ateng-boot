package io.github.atengk.boot.sample.excel.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 示例多语言国际化 Excel 导入导出视图对象
 * 演示表头占位符 {i18n:xxx} 配合 Spring MessageSource 动态转译
 *
 * @author Ateng
 * @since 2026-10-20
 */
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

    public I18nUserExcelVO() {
    }

    public I18nUserExcelVO(Long id, String name, String email, String department) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.department = department;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
