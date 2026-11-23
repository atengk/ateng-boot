package io.github.atengk.boot.admin.provider;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;

import java.util.Locale;
import java.util.Optional;

/**
 * Spring Boot Admin 控制台操作员权限角色枚举
 * <p>
 * 采用三级递进权限模型：
 * <ul>
 *     <li>{@link #VIEWER}：只读观察员，允许浏览实例看板、日志只读端点与审计/静默只读视图；禁止写操作与管理变更。</li>
 *     <li>{@link #OPERATOR}：运维操作员，具备日志级别调整、缓存清理、实例手动注销与静默窗口管理等运维写权限。</li>
 *     <li>{@link #ADMIN}：系统管理员，具备控制台全量管理与运维权限。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public enum AdminRole implements IBaseEnum<String> {

    /**
     * 只读观察员
     */
    VIEWER("VIEWER", "只读观察员"),

    /**
     * 运维操作员
     */
    OPERATOR("OPERATOR", "运维操作员"),

    /**
     * 系统管理员
     */
    ADMIN("ADMIN", "系统管理员");

    private final String code;
    private final String label;

    AdminRole(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getLabel() {
        return this.label;
    }

    /**
     * 判定当前角色是否具备运维写操作与管控变更权限（OPERATOR 及以上）
     *
     * @return true 表示具备写权限
     */
    public boolean hasWritePermission() {
        return this == OPERATOR || this == ADMIN;
    }

    /**
     * 判定当前角色是否具备系统超级管理员权限
     *
     * @return true 表示为 ADMIN 角色
     */
    public boolean hasAdminPermission() {
        return this == ADMIN;
    }

    /**
     * 按角色编码（大小写不敏感）解析枚举实例
     *
     * @param code 角色编码字符串
     * @return 匹配的角色枚举，未匹配或空白返回 empty
     */
    public static Optional<AdminRole> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return EnumUtil.fromCode(AdminRole.class, code.trim().toUpperCase(Locale.ROOT));
    }
}
