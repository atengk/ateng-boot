package io.github.atengk.boot.excel.model;

/**
 * 用户状态枚举测试类
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum UserStatusEnum {
    ENABLE("ENABLE", "已启用"),
    DISABLE("DISABLE", "已禁用");

    private final String code;
    private final String desc;

    UserStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
