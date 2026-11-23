package io.github.atengk.boot.admin.provider;

import java.util.Optional;
import java.util.Set;

/**
 * Spring Boot Admin 控制台登录身份校验扩展点 SPI
 *
 * @author Ateng
 * @since 2026-10-26
 */
public interface AdminAuthProvider {

    /**
     * 校验用户名与访问口令
     *
     * @param username 用户名
     * @param password 访问口令
     * @return true 若认证通过
     */
    boolean authenticate(String username, String password);

    /**
     * 校验凭据并返回携带角色授权的不可变控制台主体
     * <p>
     * 默认实现向后兼容仅重写 {@link #authenticate(String, String)} 的存量扩展实现，
     * 认证通过后自动授予 {@link AdminRole#ADMIN} 角色；多账号 RBAC 提供者可重写此方法签发细粒度角色。
     *
     * @param username 用户名
     * @param password 访问口令
     * @return 认证通过返回主体模型，否则返回 {@link Optional#empty()}
     */
    default Optional<AdminConsolePrincipal> authenticatePrincipal(String username, String password) {
        if (!authenticate(username, password)) {
            return Optional.empty();
        }
        String normalized = username != null ? username.trim() : "";
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(AdminConsolePrincipal.of(normalized, Set.of(AdminRole.ADMIN)));
    }
}
