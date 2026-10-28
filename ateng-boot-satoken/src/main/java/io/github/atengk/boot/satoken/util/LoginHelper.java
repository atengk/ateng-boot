package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.session.SaTerminalInfo;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.model.OnlineUser;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 强类型登录会话操作静态门面
 * 封装 Sa-Token 底层会话操作，支持多账号体系动态路由治理与 StpLogic 动态懒加载；
 * 基于 JsonUtil 提供纯净 JSON 字符串封包存储，彻底杜绝 @class 污染与反序列化白名单异常
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public final class LoginHelper {

    @Getter
    @AllArgsConstructor
    private static class RunAsContext {
        private final String targetUserId;
        private final String originalUserId;
    }

    private static final ThreadLocal<RunAsContext> RUN_AS_CONTEXT = new ThreadLocal<>();

    /**
     * 全局主账号登录类型标识（多账号体系共存时优先探测），默认 "login"
     */
    private static volatile String primaryLoginType = StpUtil.TYPE;

    private LoginHelper() {
    }

    /**
     * 获取全局配置的主账号体系类型标识，默认 "login"
     *
     * @return 主账号类型标识
     */
    public static String getPrimaryLoginType() {
        return primaryLoginType;
    }

    /**
     * 设置全局主账号体系类型标识
     *
     * @param type 主账号体系标识
     */
    public static void setPrimaryLoginType(String type) {
        if (type != null && !type.isBlank()) {
            primaryLoginType = type.trim();
        }
    }

    /**
     * 探测当前请求上下文中处于活跃登录态的账号类型 (loginType)
     * 探测优先级：
     * 1. 配置的主账号类型 (primaryLoginType)
     * 2. 默认标准账号类型 (StpUtil.TYPE = "login")
     * 3. 底层 SaManager 注册的其余账号体系
     *
     * @return 活跃账号类型标识，未登录时返回 null
     */
    public static String getActiveLoginType() {
        return getActiveLoginType(null);
    }

    /**
     * 探测当前请求上下文中处于活跃登录态的账号类型 (loginType)
     *
     * @param preferredType 优先探测的账号类型，若为空则默认使用 primaryLoginType
     * @return 活跃账号类型标识，未登录时返回 null
     */
    public static String getActiveLoginType(String preferredType) {
        String primary = (preferredType != null && !preferredType.isBlank()) ? preferredType : getPrimaryLoginType();
        if (primary != null && !primary.isBlank() && isLogin(primary)) {
            return primary;
        }
        if (primary != null && !StpUtil.TYPE.equals(primary) && isLogin(StpUtil.TYPE)) {
            return StpUtil.TYPE;
        }
        if (SaManager.stpLogicMap != null) {
            for (StpLogic logic : SaManager.stpLogicMap.values()) {
                if (logic != null && isLogin(logic.getLoginType())) {
                    return logic.getLoginType();
                }
            }
        }
        return null;
    }

    /**
     * 获取指定账号体系对应的 StpLogic 实例（动态路由核心机制）
     * 采用“预注册优先 + 动态懒加载保底”机制：
     * 1. 若为 null、空白或默认类型 "login"，直接返回 StpUtil.stpLogic；
     * 2. 优先通过 SaManager.stpLogicMap 探测是否已有预注册实例；
     * 3. 若未注册，通过线程安全双重检查锁定 (DCL) 动态实例化 new StpLogic(loginType) 并注册至 SaManager
     *
     * @param loginType 账号体系类型标识（如 "admin", "member", "app"）
     * @return 对应的 StpLogic 门面实例
     */
    public static StpLogic getStpLogic(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return StpUtil.stpLogic;
        }
        StpLogic logic = SaManager.stpLogicMap != null ? SaManager.stpLogicMap.get(loginType) : null;
        if (logic == null) {
            synchronized (LoginHelper.class) {
                logic = SaManager.stpLogicMap != null ? SaManager.stpLogicMap.get(loginType) : null;
                if (logic == null) {
                    logic = new StpLogic(loginType) {
                        @Override
                        public String splicingKeyTokenName() {
                            return super.splicingKeyTokenName() + "-" + loginType;
                        }

                        @Override
                        public String splicingKeyJustCreatedSave() {
                            return super.splicingKeyJustCreatedSave() + loginType;
                        }
                    };
                    SaManager.putStpLogic(logic);
                    log.info("动态懒加载注册 Sa-Token 独立账号体系 StpLogic [loginType={}]", loginType);
                }
            }
        }
        return logic;
    }

    // ==================== 登录操作 (Login) ====================

    /**
     * 执行用户登录（标准默认模式）
     *
     * @param loginUser 登录主体数据载荷
     */
    public static void login(LoginUser loginUser) {
        login(loginUser, new SaLoginParameter());
    }

    /**
     * 执行指定账号体系的用户登录（标准模式）
     *
     * @param loginType 账号体系标识
     * @param loginUser 登录主体数据载荷
     */
    public static void login(String loginType, LoginUser loginUser) {
        login(loginType, loginUser, new SaLoginParameter());
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
     * 执行指定账号体系的用户登录（指定设备端类型）
     *
     * @param loginType  账号体系标识
     * @param loginUser  登录主体数据载荷
     * @param deviceType 设备端类型（如 PC, APP, MINI_PROGRAM）
     */
    public static void login(String loginType, LoginUser loginUser, String deviceType) {
        login(loginType, loginUser, new SaLoginParameter().setDeviceType(deviceType));
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

        // 2. 调用底层核心 StpUtil 完成登录与令牌签发
        StpUtil.login(compositeLoginId, actualParam);

        // 3. 封包为纯净 JSON 字符串存储至当前 Token-Session，杜绝 @class 污染
        setLoginUser(loginUser);
    }

    /**
     * 执行指定账号体系的用户登录（完整登录参数）
     *
     * @param loginType      账号体系标识
     * @param loginUser      登录主体数据载荷
     * @param loginParameter Sa-Token 登录扩展参数
     */
    public static void login(String loginType, LoginUser loginUser, SaLoginParameter loginParameter) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            login(loginUser, loginParameter);
            return;
        }
        if (loginUser == null || loginUser.getUserId() == null || loginUser.getUserId().isBlank()) {
            throw new IllegalArgumentException("登录失败：登录主体或用户唯一标识 (userId) 不能为空");
        }
        SaLoginParameter actualParam = loginParameter != null ? loginParameter : new SaLoginParameter();

        // 1. 智能合成租户安全复合登录主键（防不同租户同数字主键碰撞踩踏）
        String compositeLoginId = buildCompositeLoginId(loginUser.getTenantId(), loginUser.getUserId());

        // 2. 调用底层核心 StpLogic 完成登录与令牌签发
        getStpLogic(loginType).login(compositeLoginId, actualParam);

        // 3. 封包为纯净 JSON 字符串存储至当前 Token-Session，杜绝 @class 污染
        setLoginUser(loginType, loginUser);
    }

    // ==================== 注销与下线操作 (Logout & Kickout) ====================

    /**
     * 当前会话注销下线
     */
    public static void logout() {
        StpUtil.logout();
    }

    /**
     * 指定账号体系的当前会话注销下线
     *
     * @param loginType 账号体系标识
     */
    public static void logout(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            logout();
            return;
        }
        getStpLogic(loginType).logout();
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
     * 指定账号体系下的指定 Token 注销下线
     *
     * @param loginType  账号体系标识
     * @param tokenValue Token 凭据
     */
    public static void logoutByTokenValue(String loginType, String tokenValue) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            logoutByTokenValue(tokenValue);
            return;
        }
        getStpLogic(loginType).logoutByTokenValue(tokenValue);
    }

    /**
     * 当前账号在指定设备端注销下线
     *
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void logoutByDevice(String deviceType) {
        logoutByDevice(StpUtil.TYPE, deviceType);
    }

    /**
     * 指定账号体系下的当前账号在指定设备端注销下线
     *
     * @param loginType  账号体系标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void logoutByDevice(String loginType, String deviceType) {
        Object loginId = getStpLogic(loginType).getLoginIdDefaultNull();
        if (loginId != null) {
            getStpLogic(loginType).logout(loginId, deviceType);
        }
    }

    /**
     * 指定账号注销下线
     *
     * @param loginId 登录账号唯一标识
     */
    public static void logoutByLoginId(Object loginId) {
        StpUtil.logout(loginId);
    }

    /**
     * 指定账号体系下的指定账号注销下线
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     */
    public static void logoutByLoginType(String loginType, Object loginId) {
        getStpLogic(loginType).logout(loginId);
    }

    /**
     * 指定账号在指定设备端注销下线
     *
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void logoutByLoginId(Object loginId, String deviceType) {
        StpUtil.logout(loginId, deviceType);
    }

    /**
     * 指定账号体系下的指定账号在指定设备端注销下线
     *
     * @param loginType  账号体系标识
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void logoutByLoginType(String loginType, Object loginId, String deviceType) {
        getStpLogic(loginType).logout(loginId, deviceType);
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
     * 将指定账号体系下的登录账号踢下线（全局所有设备端）
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     */
    public static void kickoutByLoginType(String loginType, Object loginId) {
        getStpLogic(loginType).kickout(loginId);
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
     * 将指定账号体系下的登录账号在指定设备端踢下线
     *
     * @param loginType  账号体系标识
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void kickoutByLoginType(String loginType, Object loginId, String deviceType) {
        getStpLogic(loginType).kickout(loginId, deviceType);
    }

    /**
     * 将指定 Token 凭据对应的会话踢下线
     *
     * @param tokenValue Token 凭据
     */
    public static void kickoutByToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return;
        }
        StpUtil.kickoutByTokenValue(tokenValue);
    }

    /**
     * 将指定账号体系下指定 Token 凭据对应的会话踢下线
     *
     * @param loginType  账号体系标识
     * @param tokenValue Token 凭据
     */
    public static void kickoutByToken(String loginType, String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return;
        }
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            kickoutByToken(tokenValue);
            return;
        }
        getStpLogic(loginType).kickoutByTokenValue(tokenValue);
    }

    /**
     * 将指定登录账号在指定设备端上的会话踢下线
     *
     * @param loginId    登录账号唯一标识
     * @param deviceType 终端设备类型（如 PC, APP）
     */
    public static void kickoutDevice(Object loginId, String deviceType) {
        if (loginId == null) {
            return;
        }
        StpUtil.kickout(loginId, deviceType);
    }

    /**
     * 将指定账号体系下的登录账号在指定设备端上的会话踢下线
     *
     * @param loginType  账号体系标识
     * @param loginId    登录账号唯一标识
     * @param deviceType 终端设备类型（如 PC, APP）
     */
    public static void kickoutDevice(String loginType, Object loginId, String deviceType) {
        if (loginId == null) {
            return;
        }
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            kickoutDevice(loginId, deviceType);
            return;
        }
        getStpLogic(loginType).kickout(loginId, deviceType);
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
     * 将指定账号体系下的登录账号顶替下线（全局所有设备端）
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     */
    public static void replacedByLoginType(String loginType, Object loginId) {
        getStpLogic(loginType).replaced(loginId);
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
     * 将指定账号体系下的登录账号在指定设备端顶替下线
     *
     * @param loginType  账号体系标识
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     */
    public static void replacedByLoginType(String loginType, Object loginId, String deviceType) {
        getStpLogic(loginType).replaced(loginId, deviceType);
    }

    // ==================== 状态校验与凭据提取 (Status & Tokens) ====================

    /**
     * 获取当前会话的登录设备端类型
     *
     * @return 设备端类型，未登录或未设置返回 null
     */
    public static String getLoginDeviceType() {
        return StpUtil.getLoginDeviceType();
    }

    /**
     * 获取指定账号体系下当前会话的登录设备端类型
     *
     * @param loginType 账号体系标识
     * @return 设备端类型，未登录或未设置返回 null
     */
    public static String getLoginDeviceType(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getLoginDeviceType();
        }
        return getStpLogic(loginType).getLoginDeviceType();
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
            return false;
        }
    }

    /**
     * 判断指定账号体系下的当前会话是否已经登录
     *
     * @param loginType 账号体系标识
     * @return true 表示已登录
     */
    public static boolean isLogin(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return isLogin();
        }
        try {
            return getStpLogic(loginType).isLogin();
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
     * 校验指定账号体系下的当前会话是否登录，未登录则抛出 NotLoginException
     *
     * @param loginType 账号体系标识
     */
    public static void checkLogin(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            checkLogin();
            return;
        }
        getStpLogic(loginType).checkLogin();
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
     * 获取指定账号体系当前会话分配的 Token 字符串值
     *
     * @param loginType 账号体系标识
     * @return Token 字符串值
     */
    public static String getTokenValue(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getTokenValue();
        }
        return getStpLogic(loginType).getTokenValue();
    }

    // ==================== 载荷存储与读取 (Payload & LoginUser) ====================

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
     * 将 LoginUser 实体纯净封包写入指定账号体系的当前 Session
     *
     * @param loginType 账号体系标识
     * @param loginUser 登录主体
     */
    public static void setLoginUser(String loginType, LoginUser loginUser) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            setLoginUser(loginUser);
            return;
        }
        StpLogic logic = getStpLogic(loginType);
        if (loginUser == null) {
            SaSession session = logic.getSession(false);
            if (session != null) {
                session.delete(SaTokenConstant.LOGIN_USER_KEY);
            }
            return;
        }
        try {
            String jsonStr = JsonUtil.toJsonString(loginUser);
            SaSession session = logic.getSession();
            if (session != null) {
                session.set(SaTokenConstant.LOGIN_USER_KEY, jsonStr);
            }
        } catch (Exception e) {
            log.error("LoginUser 序列化失败 [loginType={}]: ", loginType, e);
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
     * 获取指定账号体系当前会话绑定的强类型 LoginUser
     *
     * @param loginType 账号体系标识
     * @return LoginUser 实例，未登录或未存储时返回 null
     */
    public static LoginUser getLoginUser(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getLoginUser();
        }
        return getLoginUser(loginType, LoginUser.class);
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
     * 获取指定账号体系当前会话绑定的强类型实体（支持自定义扩展子类泛型读取）
     *
     * @param loginType 账号体系标识
     * @param clazz     目标类型 Class
     * @param <T>       泛型参数
     * @return 目标实体实例，未登录或未存储时返回 null
     */
    public static <T> T getLoginUser(String loginType, Class<T> clazz) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getLoginUser(clazz);
        }
        if (!isLogin(loginType)) {
            return null;
        }
        SaSession session = getStpLogic(loginType).getSession(false);
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
                log.warn("LoginUser 反序列化失败 [loginType={}]: json={}, targetClass={}",
                        loginType, jsonStr, clazz.getName(), e);
                return null;
            }
        }
        return null;
    }

    // ==================== 操作人身份识别与模拟 (User & RunAs) ====================

    /**
     * 获取当前操作人用户唯一标识（支持 RunAs 身份模拟，透明剥离多租户前缀）
     * 优先级：
     * 1. 瞬态代码块 RunAs (ThreadLocal)
     * 2. 会话 Session 模拟 (switch_target_user_id)
     * 3. 真实登录用户 ID
     *
     * @return 当前生效的用户唯一标识，未登录时返回 null
     */
    public static String getUserId() {
        // 1. 检查瞬态 RunAs 代码块
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null && runAsCtx.getTargetUserId() != null && !runAsCtx.getTargetUserId().isBlank()) {
            return runAsCtx.getTargetUserId();
        }

        if (!isLogin()) {
            return null;
        }

        // 2. 检查跨请求 Session 模拟
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            Object switchTarget = session.get(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
            if (switchTarget != null) {
                String targetId = String.valueOf(switchTarget).trim();
                if (!targetId.isEmpty()) {
                    return targetId;
                }
            }
        }

        // 3. 返回底层真实登录用户
        return getRealUserId();
    }

    /**
     * 获取指定账号体系下的当前操作人用户唯一标识（支持 RunAs 身份模拟，透明剥离多租户前缀）
     *
     * @param loginType 账号体系标识
     * @return 当前生效的用户唯一标识，未登录时返回 null
     */
    public static String getUserId(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getUserId();
        }
        // 1. 检查瞬态 RunAs 代码块
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null && runAsCtx.getTargetUserId() != null && !runAsCtx.getTargetUserId().isBlank()) {
            return runAsCtx.getTargetUserId();
        }

        if (!isLogin(loginType)) {
            return null;
        }

        // 2. 检查跨请求 Session 模拟
        SaSession session = getStpLogic(loginType).getSession(false);
        if (session != null) {
            Object switchTarget = session.get(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
            if (switchTarget != null) {
                String targetId = String.valueOf(switchTarget).trim();
                if (!targetId.isEmpty()) {
                    return targetId;
                }
            }
        }

        // 3. 返回底层真实登录用户
        return getRealUserId(loginType);
    }

    /**
     * 获取底层真实登录用户唯一标识（忽略 RunAs 模拟状态，透明剥离多租户前缀）
     *
     * @return 真实登录用户唯一标识，未登录时返回 null
     */
    public static String getRealUserId() {
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
     * 获取指定账号体系下底层真实登录用户唯一标识（忽略 RunAs 模拟状态，透明剥离多租户前缀）
     *
     * @param loginType 账号体系标识
     * @return 真实登录用户唯一标识，未登录时返回 null
     */
    public static String getRealUserId(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getRealUserId();
        }
        if (!isLogin(loginType)) {
            return null;
        }
        LoginUser loginUser = getLoginUser(loginType);
        if (loginUser != null && loginUser.getUserId() != null) {
            return loginUser.getUserId();
        }
        Object loginIdObj = getStpLogic(loginType).getLoginIdDefaultNull();
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
     * 获取底层真实登录用户 Long 唯一标识（忽略 RunAs 模拟状态）
     *
     * @return 真实登录用户 Long 唯一标识，未登录或非数字格式时返回 null
     */
    public static Long getRealUserIdAsLong() {
        String userId = getRealUserId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("真实用户唯一标识无法转换为 Long 数值: userId={}", userId);
            return null;
        }
    }

    /**
     * 获取指定账号体系下底层真实登录用户 Long 唯一标识（忽略 RunAs 模拟状态）
     *
     * @param loginType 账号体系标识
     * @return 真实登录用户 Long 唯一标识，未登录或非数字格式时返回 null
     */
    public static Long getRealUserIdAsLong(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getRealUserIdAsLong();
        }
        String userId = getRealUserId(loginType);
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("真实用户唯一标识无法转换为 Long 数值 [loginType={}]: userId={}", loginType, userId);
            return null;
        }
    }

    /**
     * 获取当前生效操作人唯一标识并转换为 Long 类型
     *
     * @return 当前生效用户 Long 唯一标识，未登录或非数字格式时返回 null
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
     * 获取指定账号体系下当前生效操作人唯一标识并转换为 Long 类型
     *
     * @param loginType 账号体系标识
     * @return 当前生效用户 Long 唯一标识，未登录或非数字格式时返回 null
     */
    public static Long getUserIdAsLong(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getUserIdAsLong();
        }
        String userId = getUserId(loginType);
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("用户唯一标识无法转换为 Long 数值 [loginType={}]: userId={}", loginType, userId);
            return null;
        }
    }

    /**
     * 判断当前会话或当前执行线程是否处于身份模拟态 (RunAs)
     *
     * @return true 表示处于身份模拟态
     */
    public static boolean isRunAs() {
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null) {
            return true;
        }
        if (!isLogin()) {
            return false;
        }
        SaSession session = StpUtil.getSession(false);
        if (session == null) {
            return false;
        }
        Object target = session.get(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
        return target != null && !String.valueOf(target).isBlank();
    }

    /**
     * 判断指定账号体系下的当前会话或当前执行线程是否处于身份模拟态 (RunAs)
     *
     * @param loginType 账号体系标识
     * @return true 表示处于身份模拟态
     */
    public static boolean isRunAs(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return isRunAs();
        }
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null) {
            return true;
        }
        if (!isLogin(loginType)) {
            return false;
        }
        SaSession session = getStpLogic(loginType).getSession(false);
        if (session == null) {
            return false;
        }
        Object target = session.get(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
        return target != null && !String.valueOf(target).isBlank();
    }

    /**
     * 获取身份模拟下的原始操作人唯一标识 (Original User ID)
     * 仅在处于身份模拟态时非空，未处于模拟态时返回 null
     *
     * @return 原始操作人标识，未模拟或未登录返回 null
     */
    public static String getOriginalUserId() {
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null) {
            return runAsCtx.getOriginalUserId();
        }
        if (!isLogin()) {
            return null;
        }
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            Object orig = session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            if (orig != null) {
                String origStr = String.valueOf(orig).trim();
                if (!origStr.isEmpty()) {
                    return origStr;
                }
            }
        }
        return null;
    }

    /**
     * 获取指定账号体系下身份模拟的原始操作人唯一标识 (Original User ID)
     *
     * @param loginType 账号体系标识
     * @return 原始操作人标识，未模拟或未登录返回 null
     */
    public static String getOriginalUserId(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getOriginalUserId();
        }
        RunAsContext runAsCtx = RUN_AS_CONTEXT.get();
        if (runAsCtx != null) {
            return runAsCtx.getOriginalUserId();
        }
        if (!isLogin(loginType)) {
            return null;
        }
        SaSession session = getStpLogic(loginType).getSession(false);
        if (session != null) {
            Object orig = session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            if (orig != null) {
                String origStr = String.valueOf(orig).trim();
                if (!origStr.isEmpty()) {
                    return origStr;
                }
            }
        }
        return null;
    }

    /**
     * 获取当前操作人账号
     *
     * @return 用户账号，未登录返回 null
     */
    public static String getUserAccount() {
        return getUserAccount(StpUtil.TYPE);
    }

    /**
     * 获取指定账号体系下的当前操作人账号
     *
     * @param loginType 账号体系标识
     * @return 用户账号，未登录返回 null
     */
    public static String getUserAccount(String loginType) {
        LoginUser user = getLoginUser(loginType);
        return user != null ? user.getUserAccount() : null;
    }

    /**
     * 获取当前操作人姓名/昵称
     *
     * @return 用户姓名，未登录返回 null
     */
    public static String getUserName() {
        return getUserName(StpUtil.TYPE);
    }

    /**
     * 获取指定账号体系下的当前操作人姓名/昵称
     *
     * @param loginType 账号体系标识
     * @return 用户姓名，未登录返回 null
     */
    public static String getUserName(String loginType) {
        LoginUser user = getLoginUser(loginType);
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
        if (!isLogin()) {
            return null;
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
     * 获取指定账号体系下当前登录用户归属的租户唯一标识
     *
     * @param loginType 账号体系标识
     * @return 租户标识，未设置或未登录返回 null
     */
    public static String getTenantId(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getTenantId();
        }
        LoginUser user = getLoginUser(loginType);
        if (user != null && user.getTenantId() != null) {
            return user.getTenantId();
        }
        if (!isLogin(loginType)) {
            return null;
        }
        Object loginIdObj = getStpLogic(loginType).getLoginIdDefaultNull();
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
        return getDeptId(StpUtil.TYPE);
    }

    /**
     * 获取指定账号体系下当前登录用户所属部门标识
     *
     * @param loginType 账号体系标识
     * @return 部门标识，未设置或未登录返回 null
     */
    public static String getDeptId(String loginType) {
        LoginUser user = getLoginUser(loginType);
        return user != null ? user.getDeptId() : null;
    }

    // ==================== 角色与权限查询 (Roles & Permissions) ====================

    /**
     * 获取当前登录用户拥有的角色标识集合（空安全防御）
     *
     * @return 角色集合，未登录或无角色时返回空集合
     */
    public static Set<String> getRoles() {
        if (!isLogin()) {
            return Collections.emptySet();
        }
        LoginUser loginUser = getLoginUser();
        if (loginUser != null && loginUser.getRoles() != null) {
            return loginUser.getRoles();
        }
        return StpUtil.getRoleList() != null ? Set.copyOf(StpUtil.getRoleList()) : Collections.emptySet();
    }

    /**
     * 获取指定账号体系下当前登录用户拥有的角色标识集合（空安全防御）
     *
     * @param loginType 账号体系标识
     * @return 角色集合，未登录或无角色时返回空集合
     */
    public static Set<String> getRoles(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getRoles();
        }
        if (!isLogin(loginType)) {
            return Collections.emptySet();
        }
        LoginUser user = getLoginUser(loginType);
        if (user != null && user.getRoles() != null) {
            return user.getRoles();
        }
        return getStpLogic(loginType).getRoleList() != null ? Set.copyOf(getStpLogic(loginType).getRoleList()) : Collections.emptySet();
    }

    /**
     * 获取当前登录用户拥有的权限码集合（空安全防御）
     *
     * @return 权限码集合，未登录或无权限时返回空集合
     */
    public static Set<String> getPermissions() {
        if (!isLogin()) {
            return Collections.emptySet();
        }
        LoginUser loginUser = getLoginUser();
        if (loginUser != null && loginUser.getPermissions() != null) {
            return loginUser.getPermissions();
        }
        return StpUtil.getPermissionList() != null ? Set.copyOf(StpUtil.getPermissionList()) : Collections.emptySet();
    }

    /**
     * 获取指定账号体系下当前登录用户拥有的权限码集合（空安全防御）
     *
     * @param loginType 账号体系标识
     * @return 权限码集合，未登录或无权限时返回空集合
     */
    public static Set<String> getPermissions(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getPermissions();
        }
        if (!isLogin(loginType)) {
            return Collections.emptySet();
        }
        LoginUser user = getLoginUser(loginType);
        if (user != null && user.getPermissions() != null) {
            return user.getPermissions();
        }
        return getStpLogic(loginType).getPermissionList() != null ? Set.copyOf(getStpLogic(loginType).getPermissionList()) : Collections.emptySet();
    }

    /**
     * 判断当前登录用户是否具备指定角色
     *
     * @param role 待校验角色码
     * @return true 表示具备
     */
    public static boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return getRoles().contains(role.trim());
    }

    /**
     * 判断指定账号体系下当前登录用户是否具备指定角色
     *
     * @param loginType 账号体系标识
     * @param role      待校验角色码
     * @return true 表示具备
     */
    public static boolean hasRole(String loginType, String role) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return hasRole(role);
        }
        if (role == null || role.isBlank()) {
            return false;
        }
        return getRoles(loginType).contains(role.trim());
    }

    /**
     * 判断当前登录用户是否具备指定权限码
     *
     * @param permission 待校验权限码
     * @return true 表示具备
     */
    public static boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        return getPermissions().contains(permission.trim());
    }

    /**
     * 判断指定账号体系下当前登录用户是否具备指定权限码
     *
     * @param loginType  账号体系标识
     * @param permission 待校验权限码
     * @return true 表示具备
     */
    public static boolean hasPermission(String loginType, String permission) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return hasPermission(permission);
        }
        if (permission == null || permission.isBlank()) {
            return false;
        }
        return getPermissions(loginType).contains(permission.trim());
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
     * 判断指定账号体系下当前登录用户是否具备超级管理员特权
     *
     * @param loginType 账号体系标识
     * @return true 表示具备超级管理员特权
     */
    public static boolean isSuperAdmin(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return isSuperAdmin();
        }
        Set<String> roles = getRoles(loginType);
        if (roles.contains("admin") || roles.contains("super_admin")) {
            return true;
        }
        Set<String> permissions = getPermissions(loginType);
        return permissions.contains("*") || permissions.contains("*:*:*");
    }

    // ==================== 二级安全认证 (Safe Mode) ====================

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
     * 开启指定账号体系当前会话的二级安全认证模式（强类型 Duration 规范）
     *
     * @param loginType 账号体系标识
     * @param duration  安全模式维持时长
     */
    public static void openSafeByLoginType(String loginType, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("开启二级安全认证失败：维持时长不能为空");
        }
        openSafeByLoginType(loginType, (int) duration.toSeconds());
    }

    /**
     * 开启指定账号体系当前会话的二级安全认证模式（秒级向后兼容）
     *
     * @param loginType      账号体系标识
     * @param timeoutSeconds 安全模式维持秒数
     */
    public static void openSafeByLoginType(String loginType, int timeoutSeconds) {
        getStpLogic(loginType).openSafe(timeoutSeconds);
    }

    /**
     * 开启指定账号体系当前会话指定业务的二级安全认证模式（强类型 Duration 规范）
     *
     * @param loginType 账号体系标识
     * @param service   指定业务标识
     * @param duration  安全模式维持时长
     */
    public static void openSafeByLoginType(String loginType, String service, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("开启二级安全认证失败：维持时长不能为空");
        }
        openSafeByLoginType(loginType, service, (int) duration.toSeconds());
    }

    /**
     * 开启指定账号体系当前会话指定业务的二级安全认证模式（秒级向后兼容）
     *
     * @param loginType      账号体系标识
     * @param service        指定业务标识
     * @param timeoutSeconds 安全模式维持秒数
     */
    public static void openSafeByLoginType(String loginType, String service, int timeoutSeconds) {
        getStpLogic(loginType).openSafe(service, timeoutSeconds);
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
     * 判断指定账号体系当前会话是否处于二级安全认证有效状态
     *
     * @param loginType 账号体系标识
     * @return true 表示处于安全状态
     */
    public static boolean isSafeByLoginType(String loginType) {
        return getStpLogic(loginType).isSafe();
    }

    /**
     * 判断指定账号体系当前会话指定业务是否处于二级安全认证有效状态
     *
     * @param loginType 账号体系标识
     * @param service   指定业务标识
     * @return true 表示处于安全状态
     */
    public static boolean isSafeByLoginType(String loginType, String service) {
        return getStpLogic(loginType).isSafe(service);
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
     * 校验指定账号体系当前会话是否处于二级安全认证有效状态，未通过则抛出 NotSafeException
     *
     * @param loginType 账号体系标识
     */
    public static void checkSafeByLoginType(String loginType) {
        getStpLogic(loginType).checkSafe();
    }

    /**
     * 校验指定账号体系当前会话指定业务是否处于二级安全认证有效状态，未通过则抛出 NotSafeException
     *
     * @param loginType 账号体系标识
     * @param service   指定业务标识
     */
    public static void checkSafeByLoginType(String loginType, String service) {
        getStpLogic(loginType).checkSafe(service);
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
     * 关闭指定账号体系当前会话二级认证安全状态
     *
     * @param loginType 账号体系标识
     */
    public static void closeSafeByLoginType(String loginType) {
        getStpLogic(loginType).closeSafe();
    }

    /**
     * 关闭指定账号体系当前会话指定业务的二级认证安全状态
     *
     * @param loginType 账号体系标识
     * @param service   指定业务标识
     */
    public static void closeSafeByLoginType(String loginType, String service) {
        getStpLogic(loginType).closeSafe(service);
    }

    // ==================== 账号封禁治理 (Account Disabling) ====================

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
     * 封禁指定账号体系下的指定账号（强类型 Duration 规范）
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param duration  封禁时长
     */
    public static void disableByLoginType(String loginType, Object loginId, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("封禁账号失败：封禁时长不能为空");
        }
        disableByLoginType(loginType, loginId, duration.toSeconds());
    }

    /**
     * 封禁指定账号体系下的指定账号（秒级向后兼容）
     *
     * @param loginType          账号体系标识
     * @param loginId            登录账号唯一标识
     * @param disableTimeSeconds 封禁时长（秒）
     */
    public static void disableByLoginType(String loginType, Object loginId, long disableTimeSeconds) {
        getStpLogic(loginType).disable(loginId, disableTimeSeconds);
    }

    /**
     * 封禁指定账号体系下的指定账号指定服务（强类型 Duration 规范）
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param service   指定服务标识
     * @param duration  封禁时长
     */
    public static void disableByLoginType(String loginType, Object loginId, String service, Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("封禁服务失败：封禁时长不能为空");
        }
        disableByLoginType(loginType, loginId, service, duration.toSeconds());
    }

    /**
     * 封禁指定账号体系下的指定账号指定服务（秒级向后兼容）
     *
     * @param loginType          账号体系标识
     * @param loginId            登录账号唯一标识
     * @param service            指定服务标识
     * @param disableTimeSeconds 封禁时长（秒）
     */
    public static void disableByLoginType(String loginType, Object loginId, String service, long disableTimeSeconds) {
        getStpLogic(loginType).disable(loginId, service, disableTimeSeconds);
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
     * 解封指定账号体系下的指定账号
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     */
    public static void untieDisableByLoginType(String loginType, Object loginId) {
        getStpLogic(loginType).untieDisable(loginId);
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
     * 解封指定账号体系下的指定账号指定服务
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param service   指定服务标识
     */
    public static void untieDisableByLoginType(String loginType, Object loginId, String service) {
        getStpLogic(loginType).untieDisable(loginId, service);
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
     * 判断指定账号体系下的指定账号是否已被封禁
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @return true 表示已被封禁
     */
    public static boolean isDisableByLoginType(String loginType, Object loginId) {
        return getStpLogic(loginType).isDisable(loginId);
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
     * 判断指定账号体系下的指定账号指定服务是否已被封禁
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param service   指定服务标识
     * @return true 表示已被封禁
     */
    public static boolean isDisableByLoginType(String loginType, Object loginId, String service) {
        return getStpLogic(loginType).isDisable(loginId, service);
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
     * 校验指定账号体系下的指定账号是否处于封禁状态，若被封禁则抛出 DisableServiceException
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     */
    public static void checkDisableByLoginType(String loginType, Object loginId) {
        getStpLogic(loginType).checkDisable(loginId);
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
     * 校验指定账号体系下的指定账号指定服务是否处于封禁状态，若被封禁则抛出 DisableServiceException
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param service   指定服务标识
     */
    public static void checkDisableByLoginType(String loginType, Object loginId, String service) {
        getStpLogic(loginType).checkDisable(loginId, service);
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
     * 获取指定账号体系下的指定账号封禁剩余时间（秒），-1 表示永久封禁，-2 表示未被封禁
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @return 剩余封禁秒数
     */
    public static long getDisableTimeByLoginType(String loginType, Object loginId) {
        return getStpLogic(loginType).getDisableTime(loginId);
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
     * 获取指定账号体系下的指定账号指定服务封禁剩余时间（秒），-1 表示永久封禁，-2 表示未被封禁
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @param service   指定服务标识
     * @return 剩余封禁秒数
     */
    public static long getDisableTimeByLoginType(String loginType, Object loginId, String service) {
        return getStpLogic(loginType).getDisableTime(loginId, service);
    }

    // ==================== 凭据与会话生命周期查询 (Token & Session Lifecycle) ====================

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
     * 获取指定账号体系下指定账号当前分配的 Token 字符串值
     *
     * @param loginType 账号体系标识
     * @param loginId   登录账号唯一标识
     * @return Token 字符串值，不存在返回 null
     */
    public static String getTokenValueByLoginType(String loginType, Object loginId) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getTokenValueByLoginId(loginId);
        }
        return getStpLogic(loginType).getTokenValueByLoginId(loginId);
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
     * 获取指定账号体系下指定账号在指定设备端当前分配的 Token 字符串值
     *
     * @param loginType  账号体系标识
     * @param loginId    登录账号唯一标识
     * @param deviceType 设备端类型（如 PC, APP）
     * @return Token 字符串值，不存在返回 null
     */
    public static String getTokenValueByLoginType(String loginType, Object loginId, String deviceType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getTokenValueByLoginId(loginId, deviceType);
        }
        return getStpLogic(loginType).getTokenValueByLoginId(loginId, deviceType);
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
     * 获取指定账号体系下当前 Token 的剩余有效时间（秒），-1 表示永久有效，-2 表示未登录或已失效
     *
     * @param loginType 账号体系标识
     * @return 剩余有效秒数
     */
    public static long getTokenTimeout(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getTokenTimeout();
        }
        return getStpLogic(loginType).getTokenTimeout();
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
     * 获取指定账号体系下当前 Session 的剩余有效时间（秒），-1 表示永久有效，-2 表示未登录或已失效
     *
     * @param loginType 账号体系标识
     * @return 剩余有效秒数
     */
    public static long getSessionTimeout(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            return getSessionTimeout();
        }
        return getStpLogic(loginType).getSessionTimeout();
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

    // ==================== 身份所有权与多租户校验 (Self & Tenant Assertions) ====================

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
        if (!isSelf(targetUserId)) {
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
     * 断言指定账号体系下目标用户标识为当前登录用户（非本人且非管理员时立即抛出 BusinessException 403 异常）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户标识
     */
    public static void checkSelfByLoginType(String loginType, String targetUserId) {
        checkSelfByLoginType(loginType, targetUserId, "无权访问该资源");
    }

    /**
     * 断言指定账号体系下目标用户标识为当前登录用户（带自定义提示消息）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户标识
     * @param message      越权提示消息
     */
    public static void checkSelfByLoginType(String loginType, String targetUserId, String message) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "无权访问该资源");
        }
        if (isSuperAdmin(loginType)) {
            return;
        }
        if (!isSelf(loginType, targetUserId)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "无权访问该资源");
        }
    }

    /**
     * 断言指定账号体系下目标用户 Long 标识为当前登录用户（非本人且非管理员时立即抛出 BusinessException 403 异常）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户 Long 标识
     */
    public static void checkSelfByLoginType(String loginType, Long targetUserId) {
        checkSelfByLoginType(loginType, targetUserId != null ? String.valueOf(targetUserId) : null);
    }

    /**
     * 断言指定账号体系下目标用户 Long 标识为当前登录用户（带自定义提示消息）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户 Long 标识
     * @param message      越权提示消息
     */
    public static void checkSelfByLoginType(String loginType, Long targetUserId, String message) {
        checkSelfByLoginType(loginType, targetUserId != null ? String.valueOf(targetUserId) : null, message);
    }

    /**
     * 判断目标用户标识是否为当前登录用户本人（纯身份断言，不掺杂特权豁免）
     *
     * @param targetUserId 目标用户标识
     * @return true 表示为本人
     */
    public static boolean isSelf(String targetUserId) {
        return isSelf(StpUtil.TYPE, targetUserId);
    }

    /**
     * 判断目标用户标识是否为指定账号体系下的当前登录用户本人（纯身份断言，不掺杂特权豁免）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户标识
     * @return true 表示为本人
     */
    public static boolean isSelf(String loginType, String targetUserId) {
        if (targetUserId == null || targetUserId.isBlank()) {
            return false;
        }
        String currentUserId = getUserId(loginType);
        return currentUserId != null && currentUserId.trim().equals(targetUserId.trim());
    }

    /**
     * 判断目标用户 Long 标识是否为当前登录用户本人（纯身份断言，不掺杂特权豁免）
     *
     * @param targetUserId 目标用户 Long 标识
     * @return true 表示为本人
     */
    public static boolean isSelf(Long targetUserId) {
        return isSelf(StpUtil.TYPE, targetUserId);
    }

    /**
     * 判断目标用户 Long 标识是否为指定账号体系下的当前登录用户本人（纯身份断言，不掺杂特权豁免）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户 Long 标识
     * @return true 表示为本人
     */
    public static boolean isSelf(String loginType, Long targetUserId) {
        if (targetUserId == null) {
            return false;
        }
        Long currentUserId = getUserIdAsLong(loginType);
        return currentUserId != null && currentUserId.equals(targetUserId);
    }

    /**
     * 判断目标用户标识是否为当前登录用户本人，或当前登录用户具备超级管理员特权
     *
     * @param targetUserId 目标用户标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelfOrAdmin(String targetUserId) {
        return isSelfOrAdmin(StpUtil.TYPE, targetUserId);
    }

    /**
     * 判断目标用户标识是否为指定账号体系下的当前登录用户本人，或具备超级管理员特权
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelfOrAdmin(String loginType, String targetUserId) {
        return isSuperAdmin(loginType) || isSelf(loginType, targetUserId);
    }

    /**
     * 判断目标用户 Long 标识是否为当前登录用户本人，或当前登录用户具备超级管理员特权
     *
     * @param targetUserId 目标用户 Long 标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelfOrAdmin(Long targetUserId) {
        return isSelfOrAdmin(StpUtil.TYPE, targetUserId);
    }

    /**
     * 判断目标用户 Long 标识是否为指定账号体系下的当前登录用户本人，或具备超级管理员特权
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户 Long 标识
     * @return true 表示为本人或具备超级管理员特权
     */
    public static boolean isSelfOrAdmin(String loginType, Long targetUserId) {
        return isSuperAdmin(loginType) || isSelf(loginType, targetUserId);
    }

    /**
     * 判断目标租户标识是否为当前登录用户的归属租户
     *
     * @param targetTenantId 目标租户标识
     * @return true 表示归属于目标租户
     */
    public static boolean isBelongsToTenant(String targetTenantId) {
        return isBelongsToTenant(StpUtil.TYPE, targetTenantId);
    }

    /**
     * 判断目标租户标识是否为指定账号体系下当前登录用户的归属租户
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户标识
     * @return true 表示归属于目标租户
     */
    public static boolean isBelongsToTenant(String loginType, String targetTenantId) {
        if (targetTenantId == null || targetTenantId.isBlank()) {
            return false;
        }
        String currentTenantId = getTenantId(loginType);
        return currentTenantId != null && currentTenantId.trim().equals(targetTenantId.trim());
    }

    /**
     * 判断目标租户 Long 标识是否为当前登录用户的归属租户
     *
     * @param targetTenantId 目标租户 Long 标识
     * @return true 表示归属于目标租户
     */
    public static boolean isBelongsToTenant(Long targetTenantId) {
        return isBelongsToTenant(StpUtil.TYPE, targetTenantId);
    }

    /**
     * 判断目标租户 Long 标识是否为指定账号体系下当前登录用户的归属租户
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户 Long 标识
     * @return true 表示归属于目标租户
     */
    public static boolean isBelongsToTenant(String loginType, Long targetTenantId) {
        return targetTenantId != null && isBelongsToTenant(loginType, String.valueOf(targetTenantId));
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
        if (!isBelongsToTenant(targetTenantId)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "非法跨租户数据访问");
        }
    }

    /**
     * 校验目标租户 Long 标识是否为当前登录用户的归属租户
     *
     * @param targetTenantId 目标租户 Long 标识
     */
    public static void checkBelongsToTenant(Long targetTenantId) {
        checkBelongsToTenant(targetTenantId != null ? String.valueOf(targetTenantId) : null);
    }

    /**
     * 校验目标租户 Long 标识是否为当前登录用户的归属租户（带自定义提示消息）
     *
     * @param targetTenantId 目标租户 Long 标识
     * @param message        越权提示消息
     */
    public static void checkBelongsToTenant(Long targetTenantId, String message) {
        checkBelongsToTenant(targetTenantId != null ? String.valueOf(targetTenantId) : null, message);
    }

    /**
     * 校验目标租户标识是否为指定账号体系下当前登录用户的归属租户
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户标识
     */
    public static void checkBelongsToTenantByLoginType(String loginType, String targetTenantId) {
        checkBelongsToTenantByLoginType(loginType, targetTenantId, "非法跨租户数据访问");
    }

    /**
     * 校验目标租户标识是否为指定账号体系下当前登录用户的归属租户（带自定义提示消息）
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户标识
     * @param message        越权提示消息
     */
    public static void checkBelongsToTenantByLoginType(String loginType, String targetTenantId, String message) {
        if (!isBelongsToTenant(loginType, targetTenantId)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, message != null ? message : "非法跨租户数据访问");
        }
    }

    /**
     * 校验目标租户 Long 标识是否为指定账号体系下当前登录用户的归属租户
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户 Long 标识
     */
    public static void checkBelongsToTenantByLoginType(String loginType, Long targetTenantId) {
        checkBelongsToTenantByLoginType(loginType, targetTenantId != null ? String.valueOf(targetTenantId) : null);
    }

    /**
     * 校验目标租户 Long 标识是否为指定账号体系下当前登录用户的归属租户（带自定义提示消息）
     *
     * @param loginType      账号体系标识
     * @param targetTenantId 目标租户 Long 标识
     * @param message        越权提示消息
     */
    public static void checkBelongsToTenantByLoginType(String loginType, Long targetTenantId, String message) {
        checkBelongsToTenantByLoginType(loginType, targetTenantId != null ? String.valueOf(targetTenantId) : null, message);
    }

    // ==================== 身份切换与瞬态模拟 (SwitchTo & RunAs) ====================

    /**
     * 切换当前会话为指定目标用户的身份（跨请求持久于 Session）
     * 适用于运维支撑、客服代客操作、技术排障等场景
     * 若已处于模拟态，则继续切换目标用户，但保留最初的 originalUserId，防止审计链路漂移
     *
     * @param targetUserId 目标用户唯一标识
     */
    public static void switchTo(String targetUserId) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("切换身份失败：目标用户唯一标识不能为空");
        }
        checkLogin();
        SaSession session = StpUtil.getSession();
        if (session == null) {
            throw new IllegalStateException("切换身份失败：无法获取当前会话 Session");
        }
        // 如果当前尚未处于会话模拟态，则记录底层真实登录用户为 originalUserId
        if (session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY) == null) {
            String realUserId = getRealUserId();
            if (realUserId != null && !realUserId.isBlank()) {
                session.set(SaTokenConstant.ORIGINAL_USER_ID_KEY, realUserId.trim());
            }
        }
        session.set(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY, targetUserId.trim());
        log.info("会话身份模拟切换: originalUserId={}, targetUserId={}",
                session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY), targetUserId.trim());
    }

    /**
     * 切换指定账号体系下的当前会话为指定目标用户的身份（跨请求持久于 Session）
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户唯一标识
     */
    public static void switchTo(String loginType, String targetUserId) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            switchTo(targetUserId);
            return;
        }
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("切换身份失败：目标用户唯一标识不能为空");
        }
        checkLogin(loginType);
        SaSession session = getStpLogic(loginType).getSession();
        if (session == null) {
            throw new IllegalStateException("切换身份失败：无法获取当前会话 Session");
        }
        // 如果当前尚未处于会话模拟态，则记录底层真实登录用户为 originalUserId
        if (session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY) == null) {
            String realUserId = getRealUserId(loginType);
            if (realUserId != null && !realUserId.isBlank()) {
                session.set(SaTokenConstant.ORIGINAL_USER_ID_KEY, realUserId.trim());
            }
        }
        session.set(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY, targetUserId.trim());
        log.info("会话身份模拟切换 [loginType={}]: originalUserId={}, targetUserId={}",
                loginType, session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY), targetUserId.trim());
    }

    /**
     * 切换当前会话为指定目标 Long 用户标识的身份
     *
     * @param targetUserId 目标用户 Long 标识
     */
    public static void switchTo(Long targetUserId) {
        switchTo(StpUtil.TYPE, targetUserId);
    }

    /**
     * 切换指定账号体系下的当前会话为指定目标 Long 用户标识的身份
     *
     * @param loginType    账号体系标识
     * @param targetUserId 目标用户 Long 标识
     */
    public static void switchTo(String loginType, Long targetUserId) {
        if (targetUserId == null) {
            throw new IllegalArgumentException("切换身份失败：目标用户唯一标识不能为空");
        }
        switchTo(loginType, String.valueOf(targetUserId));
    }

    /**
     * 结束当前会话的身份模拟，还原为真实登录用户身份
     */
    public static void endSwitch() {
        if (!isLogin()) {
            return;
        }
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            Object originalUserId = session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            session.delete(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            session.delete(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
            log.info("会话身份模拟结束，已恢复原始身份: originalUserId={}", originalUserId);
        }
    }

    /**
     * 结束指定账号体系下当前会话的身份模拟，还原为真实登录用户身份
     *
     * @param loginType 账号体系标识
     */
    public static void endSwitch(String loginType) {
        if (loginType == null || loginType.isBlank() || StpUtil.TYPE.equals(loginType)) {
            endSwitch();
            return;
        }
        if (!isLogin(loginType)) {
            return;
        }
        SaSession session = getStpLogic(loginType).getSession(false);
        if (session != null) {
            Object originalUserId = session.get(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            session.delete(SaTokenConstant.ORIGINAL_USER_ID_KEY);
            session.delete(SaTokenConstant.SWITCH_TARGET_USER_ID_KEY);
            log.info("会话身份模拟结束，已恢复原始身份 [loginType={}]: originalUserId={}", loginType, originalUserId);
        }
    }

    /**
     * 在当前线程瞬态切换为指定目标用户身份执行逻辑（带返回值）
     * 采用 ThreadLocal 栈式暂存现场，finally 严格恢复，杜绝线程污染与嵌套失效
     *
     * @param targetUserId 目标用户唯一标识
     * @param supplier     待执行业务代码块
     * @param <T>          返回值泛型
     * @return 执行结果
     */
    public static <T> T runAs(String targetUserId, Supplier<T> supplier) {
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new IllegalArgumentException("RunAs 执行失败：目标用户标识不能为空");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("RunAs 执行失败：执行体 supplier 不能为空");
        }
        RunAsContext previous = RUN_AS_CONTEXT.get();
        // 原始用户：若已有上一层 RunAsContext 则沿用其 originalUserId；否则若处于模拟态则取 getOriginalUserId()；否则取真实用户 ID
        String originalUserId = previous != null ? previous.getOriginalUserId()
                : (isRunAs() ? getOriginalUserId() : (getRealUserId() != null ? getRealUserId() : getUserId()));
        try {
            RUN_AS_CONTEXT.set(new RunAsContext(targetUserId.trim(), originalUserId));
            return supplier.get();
        } finally {
            if (previous != null) {
                RUN_AS_CONTEXT.set(previous);
            } else {
                RUN_AS_CONTEXT.remove();
            }
        }
    }

    /**
     * 在当前线程瞬态切换为指定目标用户身份执行逻辑（无返回值）
     *
     * @param targetUserId 目标用户唯一标识
     * @param runnable     待执行业务代码块
     */
    public static void runAs(String targetUserId, Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("RunAs 执行失败：执行体 runnable 不能为空");
        }
        runAs(targetUserId, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在当前线程瞬态切换为指定目标 Long 用户标识的身份执行逻辑（带返回值）
     *
     * @param targetUserId 目标用户 Long 标识
     * @param supplier     待执行业务代码块
     * @param <T>          返回值泛型
     * @return 执行结果
     */
    public static <T> T runAs(Long targetUserId, Supplier<T> supplier) {
        if (targetUserId == null) {
            throw new IllegalArgumentException("RunAs 执行失败：目标用户标识不能为空");
        }
        return runAs(String.valueOf(targetUserId), supplier);
    }

    /**
     * 在当前线程瞬态切换为指定目标 Long 用户标识的身份执行逻辑（无返回值）
     *
     * @param targetUserId 目标用户 Long 标识
     * @param runnable     待执行业务代码块
     */
    public static void runAs(Long targetUserId, Runnable runnable) {
        if (targetUserId == null) {
            throw new IllegalArgumentException("RunAs 执行失败：目标用户标识不能为空");
        }
        runAs(String.valueOf(targetUserId), runnable);
    }

    // ==================== 在线会话监控与治理 (Online Sessions) ====================

    /**
     * 获取当前主账号体系的在线会话总数（基于安全增量游标检索，彻底杜绝 KEYS *）
     *
     * @return 当前在线会话总数
     */
    public static long getOnlineUserCount() {
        return getOnlineUserCount(StpUtil.TYPE);
    }

    /**
     * 获取指定账号体系的在线会话总数（基于安全增量游标检索，彻底杜绝 KEYS *）
     *
     * @param loginType 账号体系标识
     * @return 当前在线会话总数
     */
    public static long getOnlineUserCount(String loginType) {
        StpLogic logic = getStpLogic(loginType);
        List<String> rawTokens = logic.searchTokenValue("", 0, -1, true);
        if (rawTokens == null || rawTokens.isEmpty()) {
            return 0L;
        }
        String tokenPrefix = logic.splicingKeyTokenValue("");
        long count = 0;
        for (String rawKey : rawTokens) {
            String tokenValue = extractPureTokenValue(rawKey, tokenPrefix);
            Object loginId = logic.getLoginIdByToken(tokenValue);
            if (isValidOnlineLoginId(loginId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 分页查询当前主账号体系的在线用户列表（支持按账号/用户名/用户标识/Token 关键字过滤）
     *
     * @param keyword 检索关键字（为空时检索全部）
     * @param page    当前页码（从 1 开始，兼容传入 0 自动按第 1 页处理）
     * @param size    每页显示条数
     * @return 分页在线用户列表
     */
    public static List<OnlineUser> searchOnlineUsers(String keyword, int page, int size) {
        return searchOnlineUsers(StpUtil.TYPE, keyword, page, size);
    }

    /**
     * 分页查询当前主账号体系的在线用户列表
     *
     * @param page 当前页码
     * @param size 每页显示条数
     * @return 分页在线用户列表
     */
    public static List<OnlineUser> searchOnlineUsers(int page, int size) {
        return searchOnlineUsers(StpUtil.TYPE, null, page, size);
    }

    /**
     * 分页查询指定账号体系的在线用户列表
     *
     * @param loginType 账号体系标识
     * @param page      当前页码
     * @param size      每页显示条数
     * @return 分页在线用户列表
     */
    public static List<OnlineUser> searchOnlineUsersByLoginType(String loginType, int page, int size) {
        return searchOnlineUsers(loginType, null, page, size);
    }

    /**
     * 分页查询指定账号体系的在线用户列表（支持按账号/用户名/用户标识/Token 关键字过滤）
     *
     * @param loginType 账号体系标识
     * @param keyword   检索关键字（支持匹配账号、用户名、用户唯一标识与 Token；为空时检索全部）
     * @param page      当前页码（从 1 开始，兼容传入 0 自动按第 1 页处理）
     * @param size      每页显示条数
     * @return 分页在线用户列表（空安全保底，无匹配时返回空集合）
     */
    public static List<OnlineUser> searchOnlineUsers(String loginType, String keyword, int page, int size) {
        if (page <= 0 || size <= 0) {
            return Collections.emptyList();
        }
        List<OnlineUser> allMatched = collectOnlineUsers(loginType, keyword);
        if (allMatched.isEmpty()) {
            return Collections.emptyList();
        }

        int actualSize = Math.min(size, 100);
        int start = (page - 1) * actualSize;
        if (start >= allMatched.size()) {
            return Collections.emptyList();
        }
        int end = Math.min(start + actualSize, allMatched.size());
        return allMatched.subList(start, end);
    }

    /**
     * 分页查询当前主账号体系的在线用户 PageResult
     *
     * @param keyword 检索关键字
     * @param page    当前页码
     * @param size    每页显示条数
     * @return 强类型 PageResult 对象
     */
    public static PageResult<OnlineUser> searchOnlineUserPage(String keyword, int page, int size) {
        return searchOnlineUserPage(StpUtil.TYPE, keyword, page, size);
    }

    /**
     * 分页查询当前主账号体系的在线用户 PageResult
     *
     * @param page 当前页码
     * @param size 每页显示条数
     * @return 强类型 PageResult 对象
     */
    public static PageResult<OnlineUser> searchOnlineUserPage(int page, int size) {
        return searchOnlineUserPage(StpUtil.TYPE, null, page, size);
    }

    /**
     * 分页查询指定账号体系的在线用户 PageResult
     *
     * @param loginType 账号体系标识
     * @param page      当前页码
     * @param size      每页显示条数
     * @return 强类型 PageResult 对象
     */
    public static PageResult<OnlineUser> searchOnlineUserPageByLoginType(String loginType, int page, int size) {
        return searchOnlineUserPage(loginType, null, page, size);
    }

    /**
     * 分页查询指定账号体系的在线用户 PageResult
     *
     * @param loginType 账号体系标识
     * @param keyword   检索关键字
     * @param page      当前页码
     * @param size      每页显示条数
     * @return 强类型 PageResult 对象
     */
    public static PageResult<OnlineUser> searchOnlineUserPage(String loginType, String keyword, int page, int size) {
        if (page <= 0 || size <= 0) {
            return PageResult.empty(Math.max(page, 1), Math.max(size, 10));
        }
        List<OnlineUser> allMatched = collectOnlineUsers(loginType, keyword);
        int total = allMatched.size();
        int actualSize = Math.min(size, 100);
        int start = (page - 1) * actualSize;

        List<OnlineUser> records;
        if (start >= total) {
            records = Collections.emptyList();
        } else {
            int end = Math.min(start + actualSize, total);
            records = allMatched.subList(start, end);
        }
        return PageResult.of(records, total, page, actualSize);
    }

    private static List<OnlineUser> collectOnlineUsers(String loginType, String keyword) {
        StpLogic logic = getStpLogic(loginType);
        List<String> rawTokens = logic.searchTokenValue("", 0, -1, true);
        if (rawTokens == null || rawTokens.isEmpty()) {
            return Collections.emptyList();
        }

        String tokenPrefix = logic.splicingKeyTokenValue("");
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;
        List<OnlineUser> matchedList = new ArrayList<>();

        for (String rawKey : rawTokens) {
            String tokenValue = extractPureTokenValue(rawKey, tokenPrefix);
            Object loginId = logic.getLoginIdByToken(tokenValue);
            if (!isValidOnlineLoginId(loginId)) {
                continue;
            }

            OnlineUser onlineUser = buildOnlineUser(logic, loginId, tokenValue);
            if (onlineUser == null) {
                continue;
            }

            if (kw != null) {
                boolean match = (onlineUser.getUserId() != null && onlineUser.getUserId().toLowerCase().contains(kw))
                        || (onlineUser.getUserAccount() != null && onlineUser.getUserAccount().toLowerCase().contains(kw))
                        || (onlineUser.getUserName() != null && onlineUser.getUserName().toLowerCase().contains(kw))
                        || (onlineUser.getTokenValue() != null && onlineUser.getTokenValue().toLowerCase().contains(kw));
                if (!match) {
                    continue;
                }
            }

            matchedList.add(onlineUser);
        }
        return matchedList;
    }

    private static OnlineUser buildOnlineUser(StpLogic logic, Object loginId, String tokenValue) {
        long timeout = logic.getTokenTimeout(tokenValue);
        if (timeout <= 0 && timeout != -1L) {
            return null;
        }

        String userId = null;
        String userAccount = null;
        String userName = null;
        String loginDevice = "default";
        String loginIp = "";
        Long loginTime = null;

        SaSession userSession = logic.getSessionByLoginId(loginId, false);
        LoginUser loginUser = null;
        if (userSession != null) {
            Object rawVal = userSession.get(SaTokenConstant.LOGIN_USER_KEY);
            if (rawVal instanceof LoginUser u) {
                loginUser = u;
            } else if (rawVal instanceof String jsonStr && !jsonStr.isBlank()) {
                try {
                    loginUser = JsonUtil.parseObject(jsonStr, LoginUser.class);
                } catch (Exception ignored) {
                }
            }

            SaTerminalInfo term = userSession.getTerminal(tokenValue);
            if (term != null) {
                if (term.getDeviceType() != null && !term.getDeviceType().isBlank()) {
                    loginDevice = term.getDeviceType();
                }
                if (term.getCreateTime() > 0) {
                    loginTime = term.getCreateTime();
                }
                if (term.getExtra("loginIp") != null) {
                    loginIp = String.valueOf(term.getExtra("loginIp"));
                }
            }

            if (loginTime == null && userSession.getCreateTime() > 0) {
                loginTime = userSession.getCreateTime();
            }
        }

        if (loginUser == null) {
            SaSession tokenSession = logic.getTokenSessionByToken(tokenValue, false);
            if (tokenSession != null) {
                Object rawVal = tokenSession.get(SaTokenConstant.LOGIN_USER_KEY);
                if (rawVal instanceof LoginUser u) {
                    loginUser = u;
                } else if (rawVal instanceof String jsonStr && !jsonStr.isBlank()) {
                    try {
                        loginUser = JsonUtil.parseObject(jsonStr, LoginUser.class);
                    } catch (Exception ignored) {
                    }
                }
                if (tokenSession.get("loginIp") != null) {
                    loginIp = String.valueOf(tokenSession.get("loginIp"));
                }
                if (tokenSession.get("loginDevice") != null) {
                    loginDevice = String.valueOf(tokenSession.get("loginDevice"));
                }
            }
        }

        if (loginUser != null) {
            userId = loginUser.getUserId();
            userAccount = loginUser.getUserAccount();
            userName = loginUser.getUserName();
            if ((loginIp == null || loginIp.isBlank()) && loginUser.getExtraData() != null && loginUser.getExtraData().get("loginIp") != null) {
                loginIp = String.valueOf(loginUser.getExtraData().get("loginIp"));
            }
        } else {
            userId = stripTenantPrefix(loginId.toString());
            userAccount = userId;
            userName = userId;
        }

        if (loginTime == null) {
            loginTime = System.currentTimeMillis();
        }

        return OnlineUser.builder()
                .userId(userId)
                .userAccount(userAccount)
                .userName(userName)
                .tokenValue(tokenValue)
                .loginDevice(loginDevice)
                .loginIp(loginIp)
                .loginTime(loginTime)
                .tokenTimeout(timeout)
                .build();
    }

    private static String extractPureTokenValue(String rawKey, String tokenPrefix) {
        if (rawKey == null) {
            return "";
        }
        if (tokenPrefix != null && !tokenPrefix.isEmpty() && rawKey.startsWith(tokenPrefix)) {
            return rawKey.substring(tokenPrefix.length());
        }
        return rawKey;
    }

    private static boolean isValidOnlineLoginId(Object loginId) {
        if (loginId == null) {
            return false;
        }
        String str = loginId.toString();
        return !"-1".equals(str) && !"-2".equals(str) && !"-3".equals(str) && !"-4".equals(str) && !"-5".equals(str);
    }

    private static String stripTenantPrefix(String loginId) {
        if (loginId != null && loginId.contains(SaTokenConstant.TENANT_DELIMITER)) {
            return loginId.substring(loginId.indexOf(SaTokenConstant.TENANT_DELIMITER) + 1);
        }
        return loginId;
    }
}
