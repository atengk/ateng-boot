package io.github.atengk.boot.excel.model;

/**
 * 用户性别枚举测试类
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum UserGenderEnum {
    MALE(1, "男"),
    FEMALE(2, "女");

    private final int code;
    private final String text;

    UserGenderEnum(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }
}
