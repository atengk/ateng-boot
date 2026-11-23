package io.github.atengk.boot.admin.security;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * Sa-Token 控制台会话与主体模型存取辅助工具
 * <p>
 * 负责在 {@link SaSession} 中安全存取 {@link AdminConsolePrincipal}，并在 Sa-Token 上下文未就绪或
 * 静态 Mock 单测场景下显式收敛 {@link SaTokenException} 语义。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public final class AdminSessionHelper {

    /**
     * SaSession 中存储控制台主体对象的标准键名
     */
    public static final String SESSION_PRINCIPAL_KEY = "ateng:admin:principal";

    private AdminSessionHelper() {
    }

    /**
     * 将已认证控制台主体写入当前 Sa-Token 会话
     *
     * @param principal 控制台操作主体
     */
    public static void savePrincipal(AdminConsolePrincipal principal) {
        if (principal == null) {
            return;
        }
        try {
            SaSession session = StpUtil.getSession();
            if (session != null) {
                session.set(SESSION_PRINCIPAL_KEY, principal);
            }
        } catch (SaTokenException ex) {
            log.debug("[Admin Session] 写入主体至 SaSession 时上下文未就绪: {}", ex.getMessage());
        }
    }

    /**
     * 判断当前请求是否已具备有效 Sa-Token 登录态
     *
     * @return true 若已登录
     */
    public static boolean isSessionAuthenticated() {
        try {
            return StpUtil.isLogin();
        } catch (SaTokenException ex) {
            log.debug("[Admin Session] 检测 Sa-Token 登录态时上下文不可用: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 读取当前已登录会话中的控制台主体模型（优先会话，兼容桥接后的请求主体）
     *
     * @param request 可选 HTTP 请求（用于读取桥接后的 {@code getUserPrincipal()}）
     * @return 当前控制台主体，未登录或缺失主体时返回 {@link Optional#empty()}
     */
    public static Optional<AdminConsolePrincipal> resolvePrincipal(HttpServletRequest request) {
        Optional<AdminConsolePrincipal> sessionPrincipal = getCurrentPrincipal();
        if (sessionPrincipal.isPresent()) {
            return sessionPrincipal;
        }
        if (request != null && request.getUserPrincipal() instanceof AdminConsolePrincipal bridged) {
            return Optional.of(bridged);
        }
        return Optional.empty();
    }

    /**
     * 读取当前已登录会话中的控制台主体模型
     *
     * @return 当前主体，未登录或会话未携带主体时返回 {@link Optional#empty()}
     */
    public static Optional<AdminConsolePrincipal> getCurrentPrincipal() {
        try {
            if (!StpUtil.isLogin()) {
                return Optional.empty();
            }
            SaSession session = StpUtil.getSession(false);
            if (session != null) {
                Object raw = session.get(SESSION_PRINCIPAL_KEY);
                AdminConsolePrincipal parsed = convertPrincipal(raw);
                if (parsed != null) {
                    return Optional.of(parsed);
                }
            }
            return Optional.empty();
        } catch (SaTokenException ex) {
            log.debug("[Admin Session] 读取 SaSession 主体时上下文不可用: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析当前操作主体用户名，若不存在已登录会话则返回回退值
     *
     * @param fallback 未登录时的回退标识（如 anonymous）
     * @return 操作者用户名或回退值
     */
    public static String getCurrentActorOrFallback(String fallback) {
        Optional<AdminConsolePrincipal> principal = getCurrentPrincipal();
        if (principal.isPresent()) {
            return principal.get().getUsername();
        }
        try {
            if (StpUtil.isLogin()) {
                String loginId = StpUtil.getLoginIdAsString();
                if (loginId != null && !loginId.isBlank()) {
                    return loginId.trim();
                }
            }
        } catch (SaTokenException ex) {
            log.debug("[Admin Session] 解析当前登录 ID 时上下文不可用: {}", ex.getMessage());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static AdminConsolePrincipal convertPrincipal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof AdminConsolePrincipal principal) {
            return principal;
        }
        if (raw instanceof Map<?, ?> map) {
            Object userObj = map.get("username");
            if (userObj == null) {
                userObj = map.get("name");
            }
            if (userObj == null || userObj.toString().isBlank()) {
                return null;
            }
            EnumSet<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
            Object rolesObj = map.get("roles");
            if (rolesObj instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof AdminRole role) {
                        roles.add(role);
                    } else if (item != null) {
                        AdminRole.fromCode(item.toString()).ifPresent(roles::add);
                    }
                }
            }
            if (roles.isEmpty()) {
                roles.add(AdminRole.VIEWER);
            }
            Map<String, Object> attrs = map.get("attributes") instanceof Map<?, ?> attrMap
                    ? (Map<String, Object>) attrMap
                    : null;
            Instant authAt = null;
            Object authAtObj = map.get("authenticatedAt");
            if (authAtObj instanceof Instant instant) {
                authAt = instant;
            } else if (authAtObj instanceof String str && !str.isBlank()) {
                try {
                    authAt = Instant.parse(str);
                } catch (DateTimeParseException ex) {
                    log.debug("[Admin Session] 会话主体 authenticatedAt 解析失败，回退当前时间: {}", str);
                }
            }
            return AdminConsolePrincipal.of(userObj.toString().trim(), roles, attrs, authAt);
        }
        return null;
    }
}
