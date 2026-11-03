package io.github.atengk.boot.social.template;

import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.context.CurrentSocialAppContextHolder;
import io.github.atengk.boot.social.exception.SocialException;
import io.github.atengk.boot.social.factory.SocialAppFactory;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.session.SocialSessionManager;
import io.github.atengk.boot.social.spi.SocialUserDetailsService;
import io.github.atengk.boot.social.ticket.BindTicketStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.model.AuthCallback;
import org.dromara.anduin.model.AuthResponse;
import org.dromara.anduin.model.AuthUser;
import org.dromara.anduin.request.AuthRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

/**
 * 社交登录与 OAuth2 核心操作门面
 * <p>
 * 统一提供标准 Web 重定向授权地址渲染、回调防伪换码认证、微信小程序 code2Session / App 原生直传登录，
 * 并与持久化 SPI {@link SocialUserDetailsService} 及会话凭据管理器 {@link SocialSessionManager} 深度联动。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RequiredArgsConstructor
public class SocialAuthTemplate {

    private final SocialAppFactory socialAppFactory;
    private final SocialAuthStateCache stateCache;
    private final BindTicketStore bindTicketStore;
    private final ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider;
    private final SocialSessionManager sessionManager;

    // ==================== 1. Web 浏览器端 OAuth2 授权跳转地址渲染 ====================

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL（使用空上下文与默认 5 分钟超时）
     *
     * @param appKey 社交应用唯一标识
     * @return 携带高熵防伪 State 的第三方完整授权重定向 URL
     */
    public String getAuthorizeUrl(String appKey) {
        return getAuthorizeUrl(appKey, null, null);
    }

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL（附带结构化深链与业务上下文）
     *
     * @param appKey  社交应用唯一标识
     * @param context 业务上下文（租户、回调目标跳转地址、自定义扩展参数）
     * @return 携带高熵防伪 State 的第三方完整授权重定向 URL
     */
    public String getAuthorizeUrl(String appKey, StateContext context) {
        return getAuthorizeUrl(appKey, context, null);
    }

    /**
     * 生成第三方 OAuth2 平台授权跳转 URL（附带结构化深链与自定义防伪超时）
     *
     * @param appKey       社交应用唯一标识
     * @param context      业务上下文
     * @param stateTimeout State 有效时长（若为空则使用默认 5 分钟）
     * @return 携带高熵防伪 State 的第三方完整授权重定向 URL
     */
    public String getAuthorizeUrl(String appKey, StateContext context, Duration stateTimeout) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }

        // 1. 组装并保存防伪 State 上下文
        StateContext safeContext = context != null ? context : new StateContext();
        Duration timeout = stateTimeout != null ? stateTimeout : SocialAuthStateCache.DEFAULT_STATE_TIMEOUT;
        String state = stateCache.generateState(safeContext, timeout);

        // 2. 绑定当前应用上下文并构建授权请求
        CurrentSocialAppContextHolder.setAppKey(appKey);
        try {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            String authorizeUrl = authRequest.authorize(state);
            log.info("Rendered authorize URL for appKey: [{}], state: [{}]", appKey, state);
            return authorizeUrl;
        } finally {
            CurrentSocialAppContextHolder.clear();
        }
    }

    // ==================== 2. Web 浏览器端 OAuth2 回调换码认证 ====================

    /**
     * 处理标准 Web OAuth2 回调换码认证（使用默认登录选项）
     *
     * @param appKey   社交应用唯一标识
     * @param callback 包含 code 和 state 的第三方回调参数
     * @return 认证结果（已登录或需引导绑定）
     */
    public SocialAuthResult login(String appKey, AuthCallback callback) {
        return login(appKey, callback, null);
    }

    /**
     * 处理标准 Web OAuth2 回调换码认证（支持多端多设备控制参数）
     *
     * @param appKey   社交应用唯一标识
     * @param callback 包含 code 和 state 的第三方回调参数
     * @param options  登录多端控制参数
     * @return 认证结果（已登录或需引导绑定）
     */
    public SocialAuthResult login(String appKey, AuthCallback callback, SocialLoginOptions options) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (callback == null) {
            throw new IllegalArgumentException("第三方回调参数 [callback] 不能为空");
        }

        SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);

        // 1. 防伪 State 前置有效性防御
        String state = callback.getState();
        if (!appConfig.isIgnoreCheckState()) {
            if (state == null || state.isBlank()) {
                throw new SocialException(SocialResultCode.SOCIAL_STATE_INVALID, "第三方回调参数中未携带防伪状态码 [state]");
            }
            if (!stateCache.containsKey(state)) {
                log.warn("OAuth2 state invalid or expired: appKey=[{}], state=[{}]", appKey, state);
                throw new SocialException(SocialResultCode.SOCIAL_STATE_INVALID, "OAuth2 状态码 State 非法、已过期或已被重复消费");
            }
        }

        // 2. 调用第三方平台换码获取标准化用户画像（若换码异常则确定性清理 State 阅后即焚防重放）
        SocialUser socialUser;
        try {
            socialUser = exchangeSocialUser(appKey, appConfig, callback);
        } catch (Throwable ex) {
            if (state != null && !state.isBlank()) {
                stateCache.removeContext(state);
            }
            throw ex;
        }

        // 3. 换码成功后，原子消费并销毁防伪 StateContext（阅后即焚）
        StateContext stateContext = null;
        if (state != null && !state.isBlank()) {
            stateContext = stateCache.removeContext(state);
        }

        // 4. 联动持久化 SPI 检索绑定的本地用户
        return resolveAuthResult(appKey, socialUser, options, stateContext);
    }

    // ==================== 3. 小程序 / App 原生 SDK 凭证直传换码认证 ====================

    /**
     * 微信小程序 (wx.login code2Session) 或 App 授权凭证直传登录
     *
     * @param appKey 社交应用唯一标识
     * @param code   授权凭证 code（小程序 js_code 或 App 授权码）
     * @return 认证结果
     */
    public SocialAuthResult directLogin(String appKey, String code) {
        return directLogin(appKey, code, null);
    }

    /**
     * 微信小程序 (wx.login code2Session) 或 App 授权凭证直传登录（支持多端选项）
     *
     * @param appKey  社交应用唯一标识
     * @param code    授权凭证 code
     * @param options 登录多端控制参数
     * @return 认证结果
     */
    public SocialAuthResult directLogin(String appKey, String code, SocialLoginOptions options) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("授权凭证 [code] 不能为空");
        }
        AuthCallback callback = AuthCallback.builder().code(code).build();
        return directLogin(appKey, callback, options);
    }

    /**
     * 第三方凭证回调对象直传登录（支持包含 extra 扩展字段的回调参数）
     *
     * @param appKey   社交应用唯一标识
     * @param callback 凭证参数封装
     * @param options  登录多端控制参数
     * @return 认证结果
     */
    public SocialAuthResult directLogin(String appKey, AuthCallback callback, SocialLoginOptions options) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (callback == null) {
            throw new IllegalArgumentException("凭据回调参数 [callback] 不能为空");
        }

        SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);

        // 直传模式不走浏览器 302 重定向流程，直接执行底层换码认证
        SocialUser socialUser = exchangeSocialUser(appKey, appConfig, callback);

        // 联动持久化 SPI 检索与会话签发
        return resolveAuthResult(appKey, socialUser, options, null);
    }

    // ==================== 4. 两阶段账号绑定与会话签发门面 ====================

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定，并签发登录会话
     *
     * @param bindTicket 两阶段绑定票据凭证
     * @param userId     本地系统用户唯一标识
     * @return 认证成功结果（包含用户 ID、Sa-Token 凭据与第三方画像快照）
     */
    public SocialAuthResult bind(String bindTicket, String userId) {
        return bind(null, bindTicket, userId, null);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定，并签发登录会话（支持多端设备控制选项）
     *
     * @param bindTicket 两阶段绑定票据凭证
     * @param userId     本地系统用户唯一标识
     * @param options    登录多端控制参数
     * @return 认证成功结果
     */
    public SocialAuthResult bind(String bindTicket, String userId, SocialLoginOptions options) {
        return bind(null, bindTicket, userId, options);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定，并签发登录会话（指定 appKey）
     *
     * @param appKey     社交应用标识（可选，若为空则自动取画像中关联的 appKey）
     * @param bindTicket 两阶段绑定票据凭证
     * @param userId     本地系统用户唯一标识
     * @return 认证成功结果
     */
    public SocialAuthResult bind(String appKey, String bindTicket, String userId) {
        return bind(appKey, bindTicket, userId, null);
    }

    /**
     * 消费两阶段绑定票据 BindTicket 完成本地用户与第三方账号绑定，并签发登录会话（指定 appKey 与多端设备控制选项）
     *
     * @param appKey     社交应用标识（可选，若为空则自动取画像中关联的 appKey）
     * @param bindTicket 两阶段绑定票据凭证
     * @param userId     本地系统用户唯一标识
     * @param options    登录多端控制参数
     * @return 认证成功结果
     */
    public SocialAuthResult bind(String appKey, String bindTicket, String userId, SocialLoginOptions options) {
        if (bindTicket == null || bindTicket.isBlank()) {
            throw new IllegalArgumentException("两阶段绑定票据 [bindTicket] 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("本地用户标识 [userId] 不能为空");
        }

        // 1. 原子消费并物理销毁 BindTicket（阅后即焚防重放）
        SocialUser socialUser = bindTicketStore.remove(bindTicket);
        if (socialUser == null) {
            log.warn("BindTicket invalid, expired or already consumed: ticket=[{}]", bindTicket);
            throw new SocialException(SocialResultCode.SOCIAL_BIND_TICKET_INVALID, "绑定凭证 BindTicket 非法、已过期或已被重复消费");
        }

        // 2. 解析生效的应用标识
        String effectiveAppKey = (appKey != null && !appKey.isBlank()) ? appKey : socialUser.getAppKey();
        if (effectiveAppKey == null || effectiveAppKey.isBlank()) {
            effectiveAppKey = socialUser.getSource();
        }

        // 3. 联动持久化业务 SPI 绑定账号
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider.getIfAvailable();
        if (detailsService != null) {
            detailsService.bind(userId, effectiveAppKey, socialUser);
        } else {
            log.warn("SocialUserDetailsService is not provided, skipping local user binding persistence: userId=[{}], appKey=[{}]", userId, effectiveAppKey);
        }

        // 4. 自动签发登录会话
        Object tokenInfo = sessionManager.createSession(userId, options, socialUser);
        log.info("Social user successfully bound and logged in: userId=[{}], appKey=[{}], openId=[{}]",
                userId, effectiveAppKey, socialUser.getOpenId());

        return SocialAuthResult.loggedIn(userId, tokenInfo, socialUser, null);
    }

    // ==================== 5. 独立获取第三方用户画像门面 ====================

    /**
     * 通过 Web 回调换码直接获取第三方标准化用户画像（仅认证不签发系统会话）
     *
     * @param appKey   社交应用唯一标识
     * @param callback 回调参数
     * @return 标准化用户画像
     */
    public SocialUser getSocialUser(String appKey, AuthCallback callback) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (callback == null) {
            throw new IllegalArgumentException("回调参数 [callback] 不能为空");
        }
        SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);
        if (!appConfig.isIgnoreCheckState() && callback.getState() != null) {
            stateCache.removeContext(callback.getState());
        }
        return exchangeSocialUser(appKey, appConfig, callback);
    }

    /**
     * 通过小程序/App凭证直接换取第三方标准化用户画像（仅认证不签发系统会话）
     *
     * @param appKey 社交应用唯一标识
     * @param code   授权凭证 code
     * @return 标准化用户画像
     */
    public SocialUser getDirectSocialUser(String appKey, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("授权凭证 [code] 不能为空");
        }
        AuthCallback callback = AuthCallback.builder().code(code).build();
        SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);
        return exchangeSocialUser(appKey, appConfig, callback);
    }

    // ==================== 5. 内部编排辅助方法 ====================

    /**
     * 执行底层第三方 HTTP 换码与用户信息拉取
     */
    private SocialUser exchangeSocialUser(String appKey, SocialAppConfig appConfig, AuthCallback callback) {
        CurrentSocialAppContextHolder.setAppKey(appKey);
        try {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            AuthResponse<AuthUser> authResponse;
            try {
                authResponse = authRequest.login(callback);
            } catch (Exception ex) {
                log.error("Third-party OAuth2 login exception for appKey: [{}]: {}", appKey, ex.getMessage(), ex);
                throw new SocialException(SocialResultCode.SOCIAL_AUTH_FAILED, "第三方授权认证异常: " + ex.getMessage(), ex);
            }

            if (authResponse == null || !authResponse.ok() || authResponse.getData() == null) {
                String errorMsg = authResponse != null ? authResponse.getMsg() : "第三方未返回有效响应";
                log.warn("Third-party OAuth2 login rejected: appKey=[{}], msg=[{}]", appKey, errorMsg);
                throw new SocialException(SocialResultCode.SOCIAL_AUTH_FAILED, "第三方授权认证失败: " + errorMsg);
            }

            SocialUser socialUser = SocialUser.fromAuthUser(authResponse.getData(), appConfig.getSource());
            if (socialUser != null) {
                socialUser.setAppKey(appKey);
            }
            return socialUser;
        } finally {
            CurrentSocialAppContextHolder.clear();
        }
    }

    /**
     * 业务身份路由决策：已绑定用户签发会话，未绑定用户签发两阶段绑定票据
     */
    private SocialAuthResult resolveAuthResult(String appKey,
                                               SocialUser socialUser,
                                               SocialLoginOptions options,
                                               StateContext stateContext) {
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider.getIfAvailable();
        String userId = detailsService != null ? detailsService.loadUserIdBySocialUser(appKey, socialUser) : null;

        if (userId != null && !userId.isBlank()) {
            // 分支 1：已绑定本地系统用户 -> 自动签发受管登录会话
            Object tokenInfo = sessionManager.createSession(userId, options, socialUser);
            log.info("Social user authenticated and logged in: appKey=[{}], userId=[{}], openId=[{}]",
                    appKey, userId, socialUser.getOpenId());
            return SocialAuthResult.loggedIn(userId, tokenInfo, socialUser, stateContext);
        } else {
            // 分支 2：未绑定本地用户 -> 签发两阶段高熵绑定票据
            String bindTicket = bindTicketStore.createTicket(socialUser);
            log.info("Social user not bound, issued bindTicket: appKey=[{}], openId=[{}]",
                    appKey, socialUser.getOpenId());
            return SocialAuthResult.requireBind(bindTicket, socialUser, stateContext);
        }
    }
}
