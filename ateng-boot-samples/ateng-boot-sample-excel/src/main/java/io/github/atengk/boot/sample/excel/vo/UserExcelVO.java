package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.excel.converter.ExcelDictConverter;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.provider.SampleDepartmentDropdownProvider;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 示例用户 Excel 导入导出视图对象
 * 集成数据脱敏、JSR-303 行级校验、防呆模板下拉批注、枚举转义与字典转义全功能
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class UserExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户主键 ID
     */
    @ExcelProperty(value = "用户ID", index = 0)
    private Long userId;

    /**
     * 用户姓名
     */
    @NotBlank(message = "用户姓名不能为空")
    @ExcelPrompt("请输入 2-20 位真实姓名，不可包含特殊字符")
    @ExcelProperty(value = "用户姓名", index = 1)
    private String username;

    /**
     * 联系手机号码
     */
    @NotBlank(message = "联系手机不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    @ExcelProperty(value = "联系手机", index = 2)
    private String phone;

    /**
     * 电子邮箱
     */
    @Email(message = "电子邮箱格式不正确")
    @ExcelSensitive(SensitiveType.EMAIL)
    @ExcelProperty(value = "电子邮箱", index = 3)
    private String email;

    /**
     * 用户性别
     */
    @ExcelDropdown({"男", "女", "未知"})
    @ExcelEnum(textField = "description")
    @ExcelProperty(value = "用户性别", index = 4)
    private SampleGenderEnum gender;

    /**
     * 所属部门
     */
    @ExcelDropdown(source = SampleDepartmentDropdownProvider.class)
    @ExcelPrompt("请从下拉列表中选择所属部门")
    @ExcelProperty(value = "所属部门", index = 5)
    private String department;

    /**
     * 账号状态代码（0-正常，1-停用）
     */
    @ExcelDict("user_status")
    @ExcelProperty(value = "账号状态", index = 6, converter = ExcelDictConverter.class)
    private String status;

    public UserExcelVO() {
    }

    public UserExcelVO(Long userId, String username, String phone, String email,
                       SampleGenderEnum gender, String department, String status) {
        this.userId = userId;
        this.username = username;
        this.phone = phone;
        this.email = email;
        this.gender = gender;
        this.department = department;
        this.status = status;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public SampleGenderEnum getGender() {
        return gender;
    }

    public void setGender(SampleGenderEnum gender) {
        this.gender = gender;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
