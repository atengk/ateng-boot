package io.github.atengk.boot.web.enums;

import io.github.atengk.boot.core.util.StringUtil;

/**
 * 枚举类短名转对外字典别名命名策略枚举
 *
 * @author Ateng
 * @since 2026-10-28
 */
public enum EnumNamingStrategy {

    /**
     * 小驼峰命名（如 UserStatusEnum -> userStatus，OrderStatus -> orderStatus）
     */
    LOWER_CAMEL_CASE {
        @Override
        public String resolve(Class<?> clazz) {
            String name = stripEnumSuffix(clazz.getSimpleName());
            return StringUtil.toCamelCase(name);
        }
    },

    /**
     * 小写下划线命名（如 UserStatusEnum -> user_status，OrderStatus -> order_status）
     */
    SNAKE_CASE {
        @Override
        public String resolve(Class<?> clazz) {
            String name = stripEnumSuffix(clazz.getSimpleName());
            return StringUtil.toSnakeCase(name);
        }
    },

    /**
     * 原始类名（如 UserStatusEnum -> UserStatusEnum）
     */
    ORIGINAL {
        @Override
        public String resolve(Class<?> clazz) {
            return clazz.getSimpleName();
        }
    };

    /**
     * 根据当前策略解析目标枚举的对外默认别名
     *
     * @param clazz 枚举类 Class
     * @return 解析后的别名
     */
    public abstract String resolve(Class<?> clazz);

    protected static String stripEnumSuffix(String name) {
        if (name == null || name.length() <= 4) {
            return name;
        }
        if (name.endsWith("Enum")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}
