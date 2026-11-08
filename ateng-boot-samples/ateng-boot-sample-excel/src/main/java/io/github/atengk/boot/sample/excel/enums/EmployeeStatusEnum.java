package io.github.atengk.boot.sample.excel.enums;

import io.github.atengk.boot.excel.annotation.ExcelEnum;

/**
 * 示例员工在职状态枚举
 * 用于在 Samples 中验证 @ExcelEnum 双向自动转义机制
 *
 * @author Ateng
 * @since 2026-11-08
 */
@ExcelEnum(codeField = "code", textField = "description")
public enum EmployeeStatusEnum {

    /**
     * 正式
     */
    REGULAR(1, "正式"),

    /**
     * 试用
     */
    PROBATION(2, "试用"),

    /**
     * 离职
     */
    RESIGNED(3, "离职");

    private final Integer code;
    private final String description;

    EmployeeStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
