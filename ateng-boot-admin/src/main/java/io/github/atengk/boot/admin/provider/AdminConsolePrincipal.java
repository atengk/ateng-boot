package io.github.atengk.boot.admin.provider;

import io.github.atengk.boot.core.util.DesensitizeUtil;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spring Boot Admin 控制台不可变操作主体模型
 * <p>
 * 同时实现 {@link Principal} 与 {@link Serializable}，用于在 Sa-Token 会话与 SBA 原生 UI
 * （{@code UiController.getUser(Principal)}）之间安全桥接当前登录操作人身份与角色权限。
 * 模型内严格禁止存储任何明文或哈希口令凭据。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class AdminConsolePrincipal implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final Set<AdminRole> roles;
    private final Map<String, Object> attributes;
    private final Instant authenticatedAt;

    private AdminConsolePrincipal(String username,
                                  Collection<AdminRole> roles,
                                  Map<String, Object> attributes,
                                  Instant authenticatedAt) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        this.username = username.trim();
        EnumSet<AdminRole> roleSet = EnumSet.noneOf(AdminRole.class);
        if (roles != null) {
            for (AdminRole role : roles) {
                if (role != null) {
                    roleSet.add(role);
                }
            }
        }
        this.roles = Collections.unmodifiableSet(roleSet);
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
        this.authenticatedAt = authenticatedAt != null ? authenticatedAt : Instant.now();
    }

    /**
     * 使用当前系统时间构造不含扩展属性的控制台主体
     *
     * @param username 登录用户名
     * @param roles    授予的角色集合
     * @return 不可变控制台主体
     */
    public static AdminConsolePrincipal of(String username, Collection<AdminRole> roles) {
        return new AdminConsolePrincipal(username, roles, Collections.emptyMap(), Instant.now());
    }

    /**
     * 构造带完整属性与认证时间戳的控制台主体
     *
     * @param username        登录用户名
     * @param roles           授予的角色集合
     * @param attributes      扩展展示属性
     * @param authenticatedAt 认证时间戳
     * @return 不可变控制台主体
     */
    public static AdminConsolePrincipal of(String username,
                                           Collection<AdminRole> roles,
                                           Map<String, Object> attributes,
                                           Instant authenticatedAt) {
        return new AdminConsolePrincipal(username, roles, attributes, authenticatedAt);
    }

    @Override
    public String getName() {
        return this.username;
    }

    /**
     * 获取登录用户名
     *
     * @return 用户名
     */
    public String getUsername() {
        return this.username;
    }

    /**
     * 获取脱敏展示用户名
     *
     * @return 脱敏后的用户名字符串
     */
    public String getMaskedUsername() {
        return maskUsername(this.username);
    }

    /**
     * 获取不可变角色集合
     *
     * @return 角色集合
     */
    public Set<AdminRole> getRoles() {
        return this.roles;
    }

    /**
     * 获取按声明顺序排列的不可变角色编码列表
     *
     * @return 角色编码列表
     */
    public List<String> getRoleCodes() {
        return this.roles.stream()
                .map(AdminRole::getCode)
                .toList();
    }

    /**
     * 获取扩展展示属性只读映射
     *
     * @return 扩展属性映射
     */
    public Map<String, Object> getAttributes() {
        return this.attributes;
    }

    /**
     * 获取认证签发时间
     *
     * @return 认证时间
     */
    public Instant getAuthenticatedAt() {
        return this.authenticatedAt;
    }

    /**
     * 判定是否拥有指定角色
     *
     * @param role 目标角色
     * @return true 若拥有该角色
     */
    public boolean hasRole(AdminRole role) {
        return role != null && this.roles.contains(role);
    }

    /**
     * 判定是否拥有任一目标角色
     *
     * @param candidates 候选角色数组
     * @return true 若命中任一角色
     */
    public boolean hasAnyRole(AdminRole... candidates) {
        if (candidates == null || candidates.length == 0) {
            return false;
        }
        for (AdminRole candidate : candidates) {
            if (hasRole(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定当前主体是否具备写操作与运维管控权限（OPERATOR 或 ADMIN）
     *
     * @return true 若具备写权限
     */
    public boolean hasWritePermission() {
        return this.roles.stream().anyMatch(AdminRole::hasWritePermission);
    }

    /**
     * 判定当前主体是否具备系统超级管理员权限（ADMIN）
     *
     * @return true 若具备 ADMIN 权限
     */
    public boolean hasAdminPermission() {
        return this.roles.stream().anyMatch(AdminRole::hasAdminPermission);
    }

    /**
     * 对用户名执行统一安全掩码
     *
     * @param username 原始用户名
     * @return 掩码后的用户名
     */
    public static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "*";
        }
        String trimmed = username.trim();
        if (trimmed.length() <= 1) {
            return "*";
        }
        if (trimmed.length() <= 3) {
            return DesensitizeUtil.mask(trimmed, 1, trimmed.length());
        }
        return DesensitizeUtil.maskKeepHeadTail(trimmed, 2, 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdminConsolePrincipal that)) {
            return false;
        }
        return Objects.equals(username, that.username)
                && Objects.equals(roles, that.roles)
                && Objects.equals(attributes, that.attributes)
                && Objects.equals(authenticatedAt, that.authenticatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, roles, attributes, authenticatedAt);
    }

    @Override
    public String toString() {
        return "AdminConsolePrincipal{username='" + getMaskedUsername() + "', roles=" + getRoleCodes() + "}";
    }
}
