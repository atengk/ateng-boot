package io.github.atengk.boot.sample.openapi.model;

import io.github.atengk.boot.core.enums.IBaseEnum;

/**
 * 用户账号状态通用枚举
 *
 * @author Ateng
 * @since 2026-10-30
 */
public enum UserStatusEnum implements IBaseEnum<Integer> {

    /**
     * 正常启用
     */
    ENABLE(1, "启用"),

    /**
     * 锁定禁用
     */
    DISABLE(0, "停用");

    private final Integer code;
    private final String label;

    UserStatusEnum(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
