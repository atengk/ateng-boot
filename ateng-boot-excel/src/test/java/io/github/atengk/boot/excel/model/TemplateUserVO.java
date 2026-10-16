package io.github.atengk.boot.excel.model;

import io.github.atengk.boot.excel.annotation.DropdownProvider;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 模板生成测试实体
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
public class TemplateUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty("用户名称")
    @ExcelPrompt("请填写 2-20 位真实中文姓名")
    private String name;

    @ExcelProperty("性别")
    @ExcelDropdown({"男", "女", "保密"})
    private String gender;

    @ExcelProperty("所属部门")
    @ExcelDropdown(source = DepartmentDropdownProvider.class)
    private String department;

    @ExcelProperty("手机号码")
    @ExcelPrompt(title = "联系方式", value = "仅支持 11 位大陆有效手机号")
    private String mobile;

    /**
     * 部门动态下拉提供者桩实现
     */
    public static class DepartmentDropdownProvider implements DropdownProvider {
        @Override
        public String[] getOptions() {
            return new String[]{"技术研发中心", "产品设计部", "市场运营部", "综合财务部"};
        }
    }
}
