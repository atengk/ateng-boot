package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import io.github.atengk.boot.sample.excel.provider.SampleCostCenterDropdownProvider;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 超长下拉隐藏字典表入职分配模板视图对象
 * <p>
 * 演示突破 Excel 单格 255 字符下拉内联限制：成本中心列表由 {@link SampleCostCenterDropdownProvider}
 * 动态提供，超过阈值后自动写入隐藏工作表 {@code _ateng_dropdown_dict} 并绑定公式下拉约束。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class OnboardingLongDropdownVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 员工姓名
     */
    @ExcelPrompt("请输入 2-20 位员工真实姓名")
    @ExcelProperty(value = "员工姓名", index = 0)
    private String name;

    /**
     * 性别（短静态内联下拉）
     */
    @ExcelDropdown({"男", "女", "未知"})
    @ExcelProperty(value = "性别", index = 1)
    private String gender;

    /**
     * 归属成本中心（超 255 字符动态下拉，自动切换隐藏字典工作表引用）
     */
    @ExcelPrompt("请选择超过 255 字符隐藏字典表维护的成本中心编码与名称")
    @ExcelDropdown(source = SampleCostCenterDropdownProvider.class)
    @ExcelProperty(value = "归属成本中心", index = 2)
    private String costCenter;

    public OnboardingLongDropdownVO() {
    }

    public OnboardingLongDropdownVO(String name, String gender, String costCenter) {
        this.name = name;
        this.gender = gender;
        this.costCenter = costCenter;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getCostCenter() {
        return costCenter;
    }

    public void setCostCenter(String costCenter) {
        this.costCenter = costCenter;
    }
}
