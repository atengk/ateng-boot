package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * 强类型登录会话操作静态门面
 * 封装 Sa-Token 底层会话操作，基于 JsonUtil 提供纯净 JSON 字符串封包存储，彻底杜绝 @class 污染与反序列化白名单异常
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public final class LoginHelper {

    private LoginHelper() {
    }

    /**
     * 执行用户登录（标准默认模式）
     *
     * @param loginUser 登录主体数据载荷
     */
    public static void login(LoginUser loginUser) {
        login(loginUser, new SaLoginParameter());
    }

    /**
     * 执行用户登录（指定设备端类型）
     *
     * @param loginUser  登录主体数据载荷
     * @param deviceType 设备端类型（如 PC, APP, MINI_PROGRAM）
     */
    public static void login(LoginUser loginUser, String deviceType) {
        login(loginUser, new SaLoginParameter().setDeviceType(deviceType));
    }

    /**
     * 执行用户登录（完整登录参数）
     *
     * @param loginUser      登录主体数据载荷
     * @param loginParameter Sa-Token 登录扩展参数
     */
    public static void login(LoginUser loginUser, SaLoginParameter loginParameter) {
        if (loginUser == null || loginUser.getUserId() == null || loginUser.getUserId().isBlank()) {
            throw new IllegalArgumentException("登录失败：登录主体或用户唯一标识 (userId) 不能为空");
        }
        SaLoginParameter actualParam = loginParameter != null ? loginParameter : new SaLoginParameter();

        // 1. 智能合成租户安全复合登录主键（防不同租户同数字主键碰撞踩踏）
        String compositeLoginId = buildCompositeLoginId(loginUser.getTenantId(), loginUser.getUserId());

        // 2. 调用底层核心完成登录与令牌签发
        StpUtil.login(compositeLoginId, actualParam);

        // 3. 封包为纯净 JSON 字符串存储至当前 Token-Session，杜绝 @class 污染
        setLoginUser(loginUser);
    }

    /**
     * 当前会话注销下线
     */
    public static void logout() {
        StpUtil.logout();
    }

    /**
     * 指定 Token 注销下线
     *
     * @param tokenValue Token 凭据
     */
    public static void logoutByTokenValue(String tokenValue) {
        StpUtil.logoutByTokenValue(tokenValue);
    }

    /**
     * 当前账号在指定设备端注销下线
     *
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void logoutByDevice(String deviceType) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            StpUtil.logout(loginId, deviceType);
        }
    }

    /**
     * 将指定登录账号踢下线（全局所有设备端）
     *
     * @param loginId 登录账号唯一标识
     */
    public static void kickout(Object loginId) {
        StpUtil.kickout(loginId);
    }

    /**
     * 将指定登录账号在指定设备端踢下线
     *
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void kickout(Object loginId, String deviceType) {
        StpUtil.kickout(loginId, deviceType);
    }

    /**
     * 获取当前会话的登录设备端类型
     *
     * @return 设备端类型，未登录或未设置返回 null
     */
    public static String getLoginDeviceType() {
        return StpUtil.getLoginDeviceType();
    }

    /**
     * 判断当前会话是否已经登录
     *
     * @return true 表示已登录
     */
    public static boolean isLogin() {
        try {
            return StpUtil.isLogin();
        } catch (Exception ignored) {
            // 在非 Web 线程或 SaToken 上下文尚未初始化的后台线程中防御性返回 false
            return false;
        }
    }

    /**
     * 校验当前会话是否登录，未登录则抛出 NotLoginException
     */
    public static void checkLogin() {
        StpUtil.checkLogin();
    }

    /**
     * 获取当前会话分配的 Token 字符串值
     *
     * @return Token 字符串值
     */
    public static String getTokenValue() {
        return StpUtil.getTokenValue();
    }

    /**
     * 将 LoginUser 实体纯净封包写入当前 Session
     *
     * @param loginUser 登录主体
     */
    public static void setLoginUser(LoginUser loginUser) {
        if (loginUser == null) {
            SaSession session = StpUtil.getSession(false);
            if (session != null) {
                session.delete(SaTokenConstant.LOGIN_USER_KEY);
            }
            return;
        }
        try {
            String jsonStr = JsonUtil.toJsonString(loginUser);
            SaSession session = StpUtil.getSession();
            if (session != null) {
                session.set(SaTokenConstant.LOGIN_USER_KEY, jsonStr);
            }
        } catch (Exception e) {
            log.error("LoginUser 序列化失败: ", e);
            throw new IllegalStateException("用户上下文序列化异常", e);
        }
    }

    /**
     * 获取当前会话绑定的强类型 LoginUser
     *
     * @return LoginUser 实例，未登录或未存储时返回 null
     */
    public static LoginUser getLoginUser() {
        return getLoginUser(LoginUser.class);
    }

    /**
     * 获取当前会话绑定的强类型实体（支持自定义扩展子类泛型读取）
     *
     * @param clazz 目标类型 Class
     * @param <T>   泛型参数
     * @return 目标实体实例，未登录或未存储时返回 null
     */
    public static <T> T getLoginUser(Class<T> clazz) {
        if (!isLogin()) {
            return null;
        }
        SaSession session = StpUtil.getSession(false);
        if (session == null) {
            return null;
        }
        Object rawVal = session.get(SaTokenConstant.LOGIN_USER_KEY);
        if (rawVal == null) {
            return null;
        }
        if (clazz.isInstance(rawVal)) {
            return clazz.cast(rawVal);
        }
        if (rawVal instanceof String jsonStr) {
            try {
                return JsonUtil.parseObject(jsonStr, clazz);
            } catch (Exception e) {
                log.warn("LoginUser 反序列化失败: json={}, targetClass={}", jsonStr, clazz.getName(), e);
                return null;
            }
        }
        return null;
    }

    /**
     * 获取当前操作人原始唯一标识（透明剥离多租户前缀）
     *
     * @return 原始用户唯一标识，未登录时返回 null
     */
    public static String getUserId() {
        if (!isLogin()) {
            return null;
        }
        LoginUser loginUser = getLoginUser();
        if (loginUser != null && loginUser.getUserId() != null) {
            return loginUser.getUserId();
        }
        Object loginIdObj = StpUtil.getLoginIdDefaultNull();
        if (loginIdObj != null) {
            String loginId = String.valueOf(loginIdObj);
            if (loginId.contains(SaTokenConstant.TENANT_DELIMITER)) {
                return loginId.substring(loginId.indexOf(SaTokenConstant.TENANT_DELIMITER) + 1);
            }
            return loginId;
        }
        return null;
    }

    /**
     * 获取当前操作人原始唯一标识并转换为 Long 类型
     *
     * @return 原始用户 Long 唯一标识，未登录或非数字格式时返回 null
     */
    public static Long getUserIdAsLong() {
        String userId = getUserId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("用户唯一标识无法转换为 Long 数值: userId={}", userId);
            return null;
        }
    }

    /**
     * 获取当前操作人账号
     *
     * @return 用户账号，未登录返回 null
     */
    public static String getUserAccount() {
        LoginUser user = getLoginUser();
        return user != null ? user.getUserAccount() : null;
    }

    /**
     * 获取当前操作人姓名/昵称
     *
     * @return 用户姓名，未登录返回 null
     */
    public static String getUserName() {
        LoginUser user = getLoginUser();
        return user != null ? user.getUserName() : null;
    }

    /**
     * 获取当前登录用户归属的租户唯一标识
     *
     * @return 租户标识，未设置或未登录返回 null
     */
    public static String getTenantId() {
        LoginUser user = getLoginUser();
        if (user != null && user.getTenantId() != null) {
            return user.getTenantId();
        }
        Object loginIdObj = StpUtil.getLoginIdDefaultNull();
        if (loginIdObj != null) {
            String loginId = String.valueOf(loginIdObj);
            if (loginId.contains(SaTokenConstant.TENANT_DELIMITER)) {
                return loginId.substring(0, loginId.indexOf(SaTokenConstant.TENANT_DELIMITER));
            }
        }
        return null;
    }

    /**
     * 获取当前登录用户所属部门标识
     *
     * @return 部门标识，未设置或未登录返回 null
     */
    public static String getDeptId() {
        LoginUser user = getLoginUser();
        return user != null ? user.getDeptId() : null;
    }

    /**
     * 获取当前登录用户拥有的角色标识集合（空安全防御）
     *
     * @return 角色集合，未登录或无角色时返回空集合
     */
    public static Set<String> getRoles() {
        LoginUser user = getLoginUser();
        return user != null ? user.getRoles() : Collections.emptySet();
    }

    /**
     * 获取当前登录用户拥有的权限码集合（空安全防御）
     *
     * @return 权限码集合，未登录或无权限时返回空集合
     */
    public static Set<String> getPermissions() {
        LoginUser user = getLoginUser();
        return user != null ? user.getPermissions() : Collections.emptySet();
    }

    /**
     * 判断当前登录用户是否具备指定角色
     *
     * @param role 待校验角色码
     * @return true 表示具备
     */
    public static boolean hasRole(String role) {
        return getRoles().contains(role);
    }

    /**
     * 判断当前登录用户是否具备指定权限码
     *
     * @param permission 待校验权限码
     * @return true 表示具备
     */
    public static boolean hasPermission(String permission) {
        return getPermissions().contains(permission);
    }

    /**
     * 判断当前登录用户是否具备超级管理员特权
     * 判定标准：角色集合包含 "admin" 或 "super_admin"，或权限集合包含 "*" 或 "*:*:*"
     *
     * @return true 表示具备超级管理员特权
     */
    public static boolean isSuperAdmin() {
        Set<String> roles = getRoles();
        if (roles.contains("admin") || roles.contains("super_admin")) {
            return true;
        }
        Set<String> permissions = getPermissions();
        return permissions.contains("*") || permissions.contains("*:*:*");
    }

    /**
     * 开启当前会话的二级安全认证模式（强类型 Duration 规范）
     *
     * @param duration 安全模式维持时长
     */
    public static void openSafe(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("开启二级安全认证失败：维持时长不能为空");
        }
        openSafe((int) duration.toSeconds());
    }

    /**
     * 开启当前会话的二级安全认证模式（秒级向后兼容）
     *
     * @param timeoutSeconds 安全模式维持秒数
     */
    public static void openSafe(int timeoutSeconds) {
        StpUtil.openSafe(timeoutSeconds);
    }

    /**
     * 开启当前会话指定业务的二级安全认证模式（强类型 Duration 规范）
     *
     * @param service  指定业务标识
     * @param duration 安全模式维持时长
     */
    public static void openSafe(String service, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("开启二级安全认证失败：维持时长不能为空");
        }
        openSafe(service, (int) duration.toSeconds());
    }

    /**
     * 开启当前会话指定业务的二级安全认证模式（秒级向后兼容）
     *
     * @param service        指定业务标识
     * @param timeoutSeconds 安全模式维持秒数
     */
    public static void openSafe(String service, int timeoutSeconds) {
        StpUtil.openSafe(service, timeoutSeconds);
    }

    /**
     * 判断当前会话是否处于二级安全认证有效状态
     *
     * @return true 表示处于安全状态
     */
    public static boolean isSafe() {
        return StpUtil.isSafe();
    }

    /**
     * 判断当前会话指定业务是否处于二级安全认证有效状态
     *
     * @param service 指定业务标识
     * @return true 表示处于安全状态
     */
    public static boolean isSafe(String service) {
        return StpUtil.isSafe(service);
    }

    /**
     * 校验当前会话是否处于二级安全认证有效状态，未通过则抛出 NotSafeException
     */
    public static void checkSafe() {
        StpUtil.checkSafe();
    }

    /**
     * 校验当前会话指定业务是否处于二级安全认证有效状态，未通过则抛出 NotSafeException
     *
     * @param service 指定业务标识
     */
    public static void checkSafe(String service) {
        StpUtil.checkSafe(service);
    }

    /**
     * 关闭当前会话二级认证安全状态
     */
    public static void closeSafe() {
        StpUtil.closeSafe();
    }

    /**
     * 关闭当前会话指定业务的二级认证安全状态
     *
     * @param service 指定业务标识
     */
    public static void closeSafe(String service) {
        StpUtil.closeSafe(service);
    }

    /**
     * 将指定登录账号顶替下线（全局所有设备端）
     *
     * @param loginId 登录账号唯一标识
     */
    public static void replaced(Object loginId) {
        StpUtil.replaced(loginId);
    }

    /**
     * 将指定登录账号在指定设备端顶替下线
     *
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void replaced(Object loginId, String deviceType) {
        StpUtil.replaced(loginId, deviceType);
    }

    /**
     * 封禁指定账号（强类型 Duration 规范）
     *
     * @param loginId  登录账号唯一标识
     * @param duration 封禁时长
     */
    public static void disable(Object loginId, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("封禁账号失败：封禁时长不能为空");
        }
        disable(loginId, duration.toSeconds());
    }

    /**
     * 封禁指定账号（秒级向后兼容）
     *
     * @param loginId            登录账号唯一标识
     * @param disableTimeSeconds 封禁时长（秒）
     */
    public static void disable(Object loginId, long disableTimeSeconds) {
        StpUtil.disable(loginId, disableTimeSeconds);
    }

    /**
     * 封禁指定账号指定服务（强类型 Duration 规范）
     *
     * @param loginId  登录账号唯一标识
     * @param service  指定服务标识
     * @param duration 封禁时长
     */
    public static void disable(Object loginId, String service, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("封禁服务失败：封禁时长不能为空");
        }
        disable(loginId, service, duration.toSeconds());
    }

    /**
     * 封禁指定账号指定服务（秒级向后兼容）
     *
     * @param loginId            登录账号唯一标识
     * @param service            指定服务标识
     * @param disableTimeSeconds 封禁时长（秒）
     */
    public static void disable(Object loginId, String service, long disableTimeSeconds) {
        StpUtil.disable(loginId, service, disableTimeSeconds);
    }

    /**
     * 解封指定账号
     *
     * @param loginId 登录账号唯一标识
     */
    public static void untieDisable(Object loginId) {
        StpUtil.untieDisable(loginId);
    }

    /**
     * 解封指定账号指定服务
     *
     * @param loginId 登录账号唯一标识
     * @param service 指定服务标识
     */
    public static void untieDisable(Object loginId, String service) {
        StpUtil.untieDisable(loginId, service);
    }

    /**
     * 判断指定账号是否已被封禁
     *
     * @param loginId 登录账号唯一标识
     * @return true 表示已被封禁
     */
    public static boolean isDisable(Object loginId) {
        return StpUtil.isDisable(loginId);
    }

    /**
     * 判断指定账号指定服务是否已被封禁
     *
     * @param loginId 登录账号唯一标识
     * @param service 指定服务标识
     * @return true 表示已被封禁
     */
    public static boolean isDisable(Object loginId, String service) {
        return StpUtil.isDisable(loginId, service);
    }

    /**
     * 校验指定账号是否处于封禁状态，若被封禁则抛出 DisableServiceException
     *
     * @param loginId 登录账号唯一标识
     */
    public static void checkDisable(Object loginId) {
        StpUtil.checkDisable(loginId);
    }

    /**
     * 校验指定账号指定服务是否处于封禁状态，若被封禁则抛出 DisableServiceException
     *
     * @param loginId 登录账号唯一标识
     * @param service 指定服务标识
     */
    public static void checkDisable(Object loginId, String service) {
        StpUtil.checkDisable(loginId, service);
    }

    /**
     * 获取指定账号封禁剩余时间（秒），-1 表示永久封禁，-2 表示未被封禁
     *
     * @param loginId 登录账号唯一标识
     * @return 剩余封禁秒数
     */
    public static long getDisableTime(Object loginId) {
        return StpUtil.getDisableTime(loginId);
    }

    /**
     * 获取指定账号指定服务封禁剩余时间（秒），-1 表示永久封禁，-2 表示未被封禁
     *
     * @param loginId 登录账号唯一标识
     * @param service 指定服务标识
     * @return 剩余封禁秒数
     */
    public static long getDisableTime(Object loginId, String service) {
        return StpUtil.getDisableTime(loginId, service);
    }

    /**
     * 获取指定账号当前分配的 Token 字符串值
     *
     * @param loginId 登录账号唯一标识
     * @return Token 字符串值，不存在返回 null
     */
    public static String getTokenValueByLoginId(Object loginId) {
        return StpUtil.getTokenValueByLoginId(loginId);
    }

    /**
     * 获取指定账号在指定设备端当前分配的 Token 字符串值
     *
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     * @return Token 字符串值，不存在返回 null
     */
    public static String getTokenValueByLoginId(Object loginId, String deviceType) {
        return StpUtil.getTokenValueByLoginId(loginId, deviceType);
    }

    /**
     * 获取当前 Token 的剩余有效时间（秒），-1 表示永久有效，-2 表示未登录或已失效
     *
     * @return 剩余有效秒数
     */
    public static long getTokenTimeout() {
        return StpUtil.getTokenTimeout();
    }

    /**
     * 获取当前 Session 的剩余有效时间（秒），-1 表示永久有效，-2 表示未登录或已失效
     *
     * @return 剩余有效秒数
     */
    public static long getSessionTimeout() {
        return StpUtil.getSessionTimeout();
    }

    /**
     * 构建多租户复合 LoginId
     */
    public static String buildCompositeLoginId(String tenantId, String userId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId.trim() + SaTokenConstant.TENANT_DELIMITER + userId.trim();
        }
        return userId.trim();
    }

    /**
     * 断言目标用户标识为当前登录用户（非本人且非管理员时立即抛出 BusinessException 403 异常）
     *
     * @param targetUserId 目标用户标识
     */
    public static void checkSelf(String targetUserId) {
        checkSelf(targetUserId, "无权访问该资源");
    }

    /**
     * 断言目标用户标识为当前登录用户（带自定义提示消息）
     *
     * @param targetUserId 目标用户标识
     * @param message      越权提示消息
     */
    public static void checkSelf(String targetUserId, String message) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "无权访问该资源");
        }
        if (isSuperAdmin()) {
            return;
        }
        String currentUserId = getUserId();
        if (currentUserId == null || !currentUserId.trim().equals(targetUserId.trim())) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "无权访问该资源");
        }
    }

    /**
     * 断言目标用户 Long 标识为当前登录用户（非本人且非管理员时立即抛出 BusinessException 403 异常）
     *
     * @param targetUserId 目标用户 Long 标识
     */
    public static void checkSelf(Long targetUserId) {
        checkSelf(targetUserId != null ? String.valueOf(targetUserId) : null);
    }

    /**
     * 断言目标用户 Long 标识为当前登录用户（带自定义提示消息）
     *
     * @param targetUserId 目标用户 Long 标识
     * @param message      越权提示消息
     */
    public static void checkSelf(Long targetUserId, String message) {
        checkSelf(targetUserId != null ? String.valueOf(targetUserId) : null, message);
    }

    /**
     * 判断目标用户标识是否为当前登录用户（或拥有超级管理员特权）
     *
     * @param targetUserId 目标用户标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelf(String targetUserId) {
        if (targetUserId == null || targetUserId.isBlank()) {
            return false;
        }
        if (isSuperAdmin()) {
            return true;
        }
        String currentUserId = getUserId();
        return currentUserId != null && currentUserId.trim().equals(targetUserId.trim());
    }

    /**
     * 判断目标用户 Long 标识是否为当前登录用户（或拥有超级管理员特权）
     *
     * @param targetUserId 目标用户 Long 标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelf(Long targetUserId) {
        return targetUserId != null && isSelf(String.valueOf(targetUserId));
    }

    /**
     * 校验目标租户标识是否为当前登录用户的归属租户
     *
     * @param targetTenantId 目标租户标识
     */
    public static void checkBelongsToTenant(String targetTenantId) {
        checkBelongsToTenant(targetTenantId, "非法跨租户数据访问");
    }

    /**
     * 校验目标租户标识是否为当前登录用户的归属租户（带自定义提示消息）
     *
     * @param targetTenantId 目标租户标识
     * @param message        越权提示消息
     */
    public static void checkBelongsToTenant(String targetTenantId, String message) {
        if (targetTenantId == null || targetTenantId.isBlank()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "非法跨租户数据访问");
        }
        String currentTenantId = getTenantId();
        if (currentTenantId == null || !currentTenantId.trim().equals(targetTenantId.trim())) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "非法跨租户数据访问");
        }
    }
}

