package io.github.atengk.boot.excel.model;

import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.converter.ExcelDictConverter;
import io.github.atengk.boot.excel.converter.ExcelEnumConverter;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;
import java.util.Objects;

/**
 * 枚举与字典双向转义测试模型
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class EnumAndDictUserVO implements Serializable {

    @ExcelProperty("用户ID")
    private Long id;

    @ExcelProperty("用户名称")
    private String username;

    /**
     * 枚举类型字段：验证仅标注 @ExcelEnum，无需显式指定 converter 自动双向转义
     */
    @ExcelProperty("用户性别")
    @ExcelEnum
    private UserGenderEnum gender;

    /**
     * 枚举类型字段：验证显式指定 converter 与自定义属性名
     */
    @ExcelProperty(value = "用户状态", converter = ExcelEnumConverter.class)
    @ExcelEnum(codeField = "code", textField = "desc")
    private UserStatusEnum status;

    /**
     * 数值编码字段：验证数值与枚举中文文本的双向转义
     */
    @ExcelProperty(value = "性别编码", converter = ExcelEnumConverter.class)
    @ExcelEnum(value = UserGenderEnum.class)
    private Integer genderCode;

    /**
     * 字典字符串字段：验证显式指定 converter 实现双向互转
     */
    @ExcelProperty(value = "系统角色", converter = ExcelDictConverter.class)
    @ExcelDict("sys_user_role")
    private String role;

    /**
     * 字典字段：验证显式指定 converter 实现双向互转
     */
    @ExcelProperty(value = "会员等级", converter = ExcelDictConverter.class)
    @ExcelDict("sys_user_level")
    private Integer level;

    public EnumAndDictUserVO() {
    }

    public EnumAndDictUserVO(Long id, String username, UserGenderEnum gender, UserStatusEnum status, Integer genderCode, String role, Integer level) {
        this.id = id;
        this.username = username;
        this.gender = gender;
        this.status = status;
        this.genderCode = genderCode;
        this.role = role;
        this.level = level;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public UserGenderEnum getGender() {
        return gender;
    }

    public void setGender(UserGenderEnum gender) {
        this.gender = gender;
    }

    public UserStatusEnum getStatus() {
        return status;
    }

    public void setStatus(UserStatusEnum status) {
        this.status = status;
    }

    public Integer getGenderCode() {
        return genderCode;
    }

    public void setGenderCode(Integer genderCode) {
        this.genderCode = genderCode;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EnumAndDictUserVO that = (EnumAndDictUserVO) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(username, that.username) &&
                gender == that.gender &&
                status == that.status &&
                Objects.equals(genderCode, that.genderCode) &&
                Objects.equals(role, that.role) &&
                Objects.equals(level, that.level);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username, gender, status, genderCode, role, level);
    }
}
