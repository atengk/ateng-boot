package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelMerge;
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 部门员工纵向单元格合并示例视图对象
 * 演示 @ExcelMerge 纵向相邻相同值单元格自动合并
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExcelTheme(theme = ExcelThemeEnum.WARM_ORANGE)
public class DepartmentUserMergeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 部门名称（相同部门相邻行自动合并）
     */
    @ExcelMerge
    @ExcelProperty(value = "部门名称", index = 0)
    private String department;

    /**
     * 员工姓名
     */
    @ExcelProperty(value = "员工姓名", index = 1)
    private String username;

    /**
     * 岗位
     */
    @ExcelProperty(value = "岗位职责", index = 2)
    private String post;

    public DepartmentUserMergeVO() {
    }

    public DepartmentUserMergeVO(String department, String username, String post) {
        this.department = department;
        this.username = username;
        this.post = post;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPost() {
        return post;
    }

    public void setPost(String post) {
        this.post = post;
    }
}
