package io.github.atengk.boot.sample.web.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;

import java.util.Map;

/**
 * 演示用户状态通用枚举
 *
 * @author Ateng
 * @since 2026-10-28
 */
public enum UserStatusEnum implements IBaseEnum<Integer> {

    ACTIVE(1, "启用"),
    DISABLED(2, "禁用") {
        @Override
        public Boolean getDisabled() {
            return true;
        }

        @Override
        public Map<String, Object> getExtra() {
            return Map.of("color", "red", "reason", "违规停用");
        }
    },
    LOCKED(3, "锁定");

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
