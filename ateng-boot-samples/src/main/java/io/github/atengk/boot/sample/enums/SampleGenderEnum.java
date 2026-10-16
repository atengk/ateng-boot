package io.github.atengk.boot.sample.enums;

import io.github.atengk.boot.excel.annotation.ExcelEnum;

/**
 * 示例用户性别枚举
 * 用于在 Samples 中验证 @ExcelEnum 双向自动转义机制
 *
 * @author Ateng
 * @since 2026-10-17
 */
@ExcelEnum(codeField = "code", textField = "description")
public enum SampleGenderEnum {

    /**
     * 男性
     */
    MALE(1, "男"),

    /**
     * 女性
     */
    FEMALE(2, "女"),

    /**
     * 未知
     */
    UNKNOWN(0, "未知");

    private final Integer code;
    private final String description;

    SampleGenderEnum(Integer code, String description) {
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
