package io.github.atengk.boot.sample.excel.provider;

import io.github.atengk.boot.excel.annotation.DropdownProvider;

/**
 * 示例部门动态下拉提供者
 * 配合 @ExcelDropdown(source = SampleDepartmentDropdownProvider.class) 使用，
 * 演示在防呆导入模板中动态加载下拉选项
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class SampleDepartmentDropdownProvider implements DropdownProvider {

    @Override
    public String[] getOptions() {
        return new String[]{"研发部", "市场部", "财务部", "运营部", "人力资源部"};
    }
}
