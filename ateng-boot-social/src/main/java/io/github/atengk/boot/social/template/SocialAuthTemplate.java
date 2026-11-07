package io.github.atengk.boot.social.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.context.CurrentSocialAppContextHolder;
import io.github.atengk.boot.social.event.SocialAuthenticatedEvent;
import io.github.atengk.boot.social.event.SocialBindTicketIssuedEvent;
import io.github.atengk.boot.social.event.SocialBoundEvent;
import io.github.atengk.boot.social.event.SocialUnboundEvent;
import io.github.atengk.boot.social.exception.SocialAlreadyBoundException;
import io.github.atengk.boot.social.exception.SocialAuthException;
import io.github.atengk.boot.social.exception.SocialException;
import io.github.atengk.boot.social.exception.SocialStateExpiredException;
import io.github.atengk.boot.social.exception.SocialUnbindRejectedException;
import io.github.atengk.boot.social.factory.SocialAppFactory;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialToken;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import io.github.atengk.boot.social.session.SocialSessionManager;
import io.github.atengk.boot.social.spi.SocialUserDetailsService;
import io.github.atengk.boot.social.support.SocialRedirectUriValidator;
import io.github.atengk.boot.social.ticket.BindTicketStore;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.model.AuthCallback;
import org.dromara.anduin.model.AuthResponse;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import org.dromara.anduin.request.AuthRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

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
public class SocialAuthTemplate implements ApplicationEventPublisherAware {

    private final SocialAppFactory socialAppFactory;
    private final SocialAuthStateCache stateCache;
    private final BindTicketStore bindTicketStore;
    private final ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider;
    private final SocialSessionManager sessionManager;
    private final AtengSocialProperties properties;
    private ApplicationEventPublisher eventPublisher;

    /**
     * 基础五参构造函数
     *
     * @param socialAppFactory                  社交应用实例工厂
     * @param stateCache                        防伪状态缓存
     * @param bindTicketStore                   两阶段绑定票据仓库
     * @param userDetailsServiceObjectProvider  持久化业务扩展点提供者
     * @param sessionManager                    会话管理器
     */
    public SocialAuthTemplate(
            SocialAppFactory socialAppFactory,
            SocialAuthStateCache stateCache,
            BindTicketStore bindTicketStore,
            ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider,
            SocialSessionManager sessionManager) {
        this(socialAppFactory, stateCache, bindTicketStore, userDetailsServiceObjectProvider, sessionManager, null, null);
    }

    /**
     * 六参构造函数（支持直接注入事件发布者）
     *
     * @param socialAppFactory                  社交应用实例工厂
     * @param stateCache                        防伪状态缓存
     * @param bindTicketStore                   两阶段绑定票据仓库
     * @param userDetailsServiceObjectProvider  持久化业务扩展点提供者
     * @param sessionManager                    会话管理器
     * @param eventPublisher                    领域事件发布者
     */
    public SocialAuthTemplate(
            SocialAppFactory socialAppFactory,
            SocialAuthStateCache stateCache,
            BindTicketStore bindTicketStore,
            ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider,
            SocialSessionManager sessionManager,
            ApplicationEventPublisher eventPublisher) {
        this(socialAppFactory, stateCache, bindTicketStore, userDetailsServiceObjectProvider, sessionManager, null, eventPublisher);
    }

    /**
     * 全参构造函数（支持配置属性与事件发布者）
     *
     * @param socialAppFactory                  社交应用实例工厂
     * @param stateCache                        防伪状态缓存
     * @param bindTicketStore                   两阶段绑定票据仓库
     * @param userDetailsServiceObjectProvider  持久化业务扩展点提供者
     * @param sessionManager                    会话管理器
     * @param properties                        社交登录配置属性
     * @param eventPublisher                    领域事件发布者
     */
    public SocialAuthTemplate(
            SocialAppFactory socialAppFactory,
            SocialAuthStateCache stateCache,
            BindTicketStore bindTicketStore,
            ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider,
            SocialSessionManager sessionManager,
            AtengSocialProperties properties,
            ApplicationEventPublisher eventPublisher) {
        this.socialAppFactory = socialAppFactory;
        this.stateCache = stateCache;
        this.bindTicketStore = bindTicketStore;
        this.userDetailsServiceObjectProvider = userDetailsServiceObjectProvider;
        this.sessionManager = sessionManager;
        this.properties = properties != null ? properties : new AtengSocialProperties();
        this.eventPublisher = eventPublisher;
    }

    public AtengSocialProperties getProperties() {
        return properties;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

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
        if ((safeContext.getTenantId() == null || safeContext.getTenantId().isBlank())
                && TenantContextHolder.getTenantId() != null
                && !TenantContextHolder.getTenantId().isBlank()) {
            safeContext.setTenantId(TenantContextHolder.getTenantId());
        }

        // 开放重定向安全防卫
        if (safeContext.getRedirectUrl() != null && !safeContext.getRedirectUrl().isBlank()) {
            SocialRedirectUriValidator.validate(safeContext.getRedirectUrl(), properties.getAllowedRedirectDomains());
        }

        Duration timeout = stateTimeout != null ? stateTimeout : (properties.getStateTimeout() != null ? properties.getStateTimeout() : SocialAuthStateCache.DEFAULT_STATE_TIMEOUT);
        String state = stateCache.generateState(safeContext, timeout);

        // 2. 绑定当前应用上下文并构建授权请求
        return executeWithAppKey(appKey, () -> {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            String authorizeUrl = authRequest.authorize(state);
            log.info("Rendered authorize URL for appKey: [{}], state: [{}]", appKey, state);
            return authorizeUrl;
        });
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
                throw new SocialStateExpiredException("第三方回调参数中未携带防伪状态码 [state]");
            }
            if (!stateCache.containsKey(state)) {
                log.warn("OAuth2 state invalid or expired: appKey=[{}], state=[{}]", appKey, state);
                throw new SocialStateExpiredException(state);
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

        // 2. 解析生效的应用标识并防御跨应用篡改
        if (appKey != null && !appKey.isBlank() && socialUser.getAppKey() != null && !appKey.equals(socialUser.getAppKey())) {
            log.warn("BindTicket appKey mismatch: ticketAppKey=[{}], paramAppKey=[{}]", socialUser.getAppKey(), appKey);
            throw new SocialException("绑定凭据关联的应用标识与当前参数不匹配");
        }
        String effectiveAppKey = (appKey != null && !appKey.isBlank()) ? appKey : socialUser.getAppKey();
        if (effectiveAppKey == null || effectiveAppKey.isBlank()) {
            effectiveAppKey = socialUser.getSource();
        }

        // 3. 跨号抢占冲突防卫拦截与持久化业务 SPI 绑定账号
        checkConflictAndBind(userId, effectiveAppKey, socialUser);

        // 4. 广播账号绑定完成领域事件
        publishEvent(new SocialBoundEvent(this, userId, effectiveAppKey, socialUser));

        // 5. 自动签发登录会话并广播登录认证成功事件
        Object tokenInfo = sessionManager.createSession(userId, options, socialUser);
        log.info("Social user successfully bound and logged in: userId=[{}], appKey=[{}], openId=[{}]",
                userId, effectiveAppKey, socialUser.getOpenId());

        publishEvent(new SocialAuthenticatedEvent(this, userId, effectiveAppKey, socialUser, options, null));

        return SocialAuthResult.loggedIn(userId, tokenInfo, socialUser, null);
    }

    /**
     * 个人中心已登录态直接绑定第三方社交账号（基于授权凭据 code 直传模式，如微信小程序）
     *
     * @param userId 本地系统用户唯一标识
     * @param appKey 社交应用唯一标识
     * @param code   授权凭证 code
     * @return 绑定的第三方标准化用户画像
     */
    public SocialUser bindDirect(String userId, String appKey, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("授权凭证 [code] 不能为空");
        }
        AuthCallback callback = AuthCallback.builder().code(code).build();
        return doBindDirect(userId, appKey, callback, false);
    }

    /**
     * 个人中心已登录态直接绑定第三方社交账号（基于 Web 重定向回调参数模式，包含防伪 State 核验）
     *
     * @param userId   本地系统用户唯一标识
     * @param appKey   社交应用唯一标识
     * @param callback 第三方回调凭证参数
     * @return 绑定的第三方标准化用户画像
     */
    public SocialUser bindDirect(String userId, String appKey, AuthCallback callback) {
        return doBindDirect(userId, appKey, callback, true);
    }

    private SocialUser doBindDirect(String userId, String appKey, AuthCallback callback, boolean checkState) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("本地用户标识 [userId] 不能为空");
        }
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (callback == null) {
            throw new IllegalArgumentException("回调参数 [callback] 不能为空");
        }

        SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);

        String state = callback.getState();
        StateContext stateContext = null;
        if (checkState && !appConfig.isIgnoreCheckState()) {
            if (state == null || state.isBlank()) {
                throw new SocialStateExpiredException("第三方回调参数中未携带防伪状态码 [state]");
            }
            if (!stateCache.containsKey(state)) {
                log.warn("OAuth2 state invalid or expired: appKey=[{}], state=[{}]", appKey, state);
                throw new SocialStateExpiredException(state);
            }
            stateContext = stateCache.getContext(state);
        }

        SocialUser socialUser;
        try {
            socialUser = exchangeSocialUser(appKey, appConfig, callback);
        } finally {
            // 换码后原子消费并销毁防伪 StateContext（阅后即焚防重放）
            if (state != null && !state.isBlank()) {
                stateCache.removeContext(state);
            }
        }

        // 若 State 包含租户标识，基于闭包确定性透传租户上下文并在执行后完全恢复现场
        String tenantId = (stateContext != null && stateContext.getTenantId() != null && !stateContext.getTenantId().isBlank())
                ? stateContext.getTenantId()
                : null;
        if (tenantId != null && !tenantId.isBlank()) {
            return TenantContextHolder.runWithTenant(tenantId, () -> executeDirectBindPersistence(userId, appKey, socialUser));
        }
        return executeDirectBindPersistence(userId, appKey, socialUser);
    }

    private SocialUser executeDirectBindPersistence(String userId, String appKey, SocialUser socialUser) {
        // 跨号抢占冲突防卫拦截与持久化业务 SPI 绑定账号
        checkConflictAndBind(userId, appKey, socialUser);

        log.info("Social account directly bound successfully: userId=[{}], appKey=[{}], openId=[{}]",
                userId, appKey, socialUser.getOpenId());
        publishEvent(new SocialBoundEvent(this, userId, appKey, socialUser));
        return socialUser;
    }

    /**
     * 校验跨号抢占冲突并调用持久化业务 SPI 绑定账号
     *
     * @param userId     本地系统用户唯一标识
     * @param appKey     社交应用唯一标识
     * @param socialUser 第三方用户信息
     */
    private void checkConflictAndBind(String userId, String appKey, SocialUser socialUser) {
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider != null
                ? userDetailsServiceObjectProvider.getIfAvailable()
                : null;
        if (detailsService != null) {
            String existingUserId = detailsService.loadUserIdBySocialUser(appKey, socialUser);
            if (existingUserId != null && !existingUserId.isBlank() && !existingUserId.equals(userId)) {
                log.warn("Social account already bound to another user: appKey=[{}], openId=[{}], existingUserId=[{}], currentUserId=[{}]",
                        appKey, socialUser.getOpenId(), existingUserId, userId);
                throw new SocialAlreadyBoundException("该第三方社交账号已被其他本地用户绑定: existingUserId=" + existingUserId
                        + ", appKey=" + appKey + ", openId=" + socialUser.getOpenId());
            }
            detailsService.bindSocialUser(userId, appKey, socialUser);
        } else {
            log.warn("SocialUserDetailsService is not provided, skipping local user binding persistence: userId=[{}], appKey=[{}]", userId, appKey);
        }
    }

    /**
     * 查询指定本地用户已绑定的第三方社交账号列表
     *
     * @param userId 本地系统用户唯一标识
     * @return 已绑定的第三方账号视图列表（保底返回空列表，严禁返回 null）
     */
    public List<SocialBoundAccount> listBoundAccounts(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("本地用户标识 [userId] 不能为空");
        }
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider != null
                ? userDetailsServiceObjectProvider.getIfAvailable()
                : null;
        if (detailsService == null) {
            return Collections.emptyList();
        }
        List<SocialBoundAccount> accounts = detailsService.listBoundAccounts(userId);
        return accounts != null ? accounts : Collections.emptyList();
    }

    // ==================== 5. 解绑操作与孤儿账号安全防卫 ====================

    /**
     * 解除本地用户与第三方社交平台的绑定关系
     * <p>
     * 在执行解绑前显式调用 {@link SocialUserDetailsService#canUnbind(String, String)} 检查业务层不变量。
     * 若返回 false（如该用户未绑定手机号或未设独立密码），刚性拦截并抛出受管业务异常 {@link SocialUnbindRejectedException}；
     * 解绑成功后，原子广播 {@link SocialUnboundEvent} 领域事件，支撑下游安全审计与流水记录。
     * </p>
     *
     * @param userId 本地用户唯一标识
     * @param appKey 社交应用唯一标识
     * @throws IllegalArgumentException       当 userId 或 appKey 为空时
     * @throws SocialUnbindRejectedException 若业务层孤儿账号核验未通过时
     */
    public void unbind(String userId, String appKey) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("本地用户标识 [userId] 不能为空");
        }
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }

        // 1. 获取持久化 SPI 扩展点（必须配置以保障孤儿账号防卫与持久化闭环）
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider.getIfAvailable();
        if (detailsService == null) {
            throw new SocialException("未配置 SocialUserDetailsService 业务持久化扩展点，无法执行账号解绑与孤儿防卫");
        }

        // 2. 孤儿死号前置安全防卫：核验业务层不变量
        boolean canUnbind;
        try {
            canUnbind = detailsService.canUnbind(userId, appKey);
        } catch (Exception ex) {
            throw new SocialException("孤儿账号解绑核验执行异常: " + ex.getMessage(), ex);
        }

        if (!canUnbind) {
            log.warn("Social unbinding rejected by safety guard: userId=[{}], appKey=[{}]", userId, appKey);
            throw new SocialUnbindRejectedException(userId, appKey);
        }

        // 3. 执行持久化解绑
        try {
            detailsService.unbind(userId, appKey);
        } catch (SocialException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SocialException("解除第三方账号绑定异常: " + ex.getMessage(), ex);
        }

        log.info("Social user successfully unbound: userId=[{}], appKey=[{}]", userId, appKey);

        // 4. 原子广播领域事件通知下游审计
        publishEvent(new SocialUnboundEvent(this, userId, appKey));
    }

    /**
     * 发布 Spring 领域事件
     *
     * @param event 待发布的领域事件
     */
    protected void publishEvent(ApplicationEvent event) {
        if (this.eventPublisher != null && event != null) {
            this.eventPublisher.publishEvent(event);
        }
    }

    // ==================== 6. 独立获取第三方用户画像门面 ====================

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
        String state = callback.getState();
        if (!appConfig.isIgnoreCheckState()) {
            if (state == null || state.isBlank()) {
                throw new SocialStateExpiredException("第三方回调参数中未携带防伪状态码 [state]");
            }
            if (!stateCache.containsKey(state)) {
                log.warn("OAuth2 state invalid or expired: appKey=[{}], state=[{}]", appKey, state);
                throw new SocialStateExpiredException(state);
            }
            stateCache.removeContext(state);
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

    // ==================== 7. 第三方 OAuth2 访问令牌续期与授权吊销门面 ====================

    /**
     * 刷新第三方 OAuth2 访问令牌（基于 refreshToken 字符串）
     *
     * @param appKey       社交应用唯一标识
     * @param refreshToken 刷新令牌
     * @return 刷新后的最新令牌领域模型
     */
    public SocialToken refreshToken(String appKey, String refreshToken) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("刷新令牌 [refreshToken] 不能为空");
        }
        return refreshToken(appKey, AuthToken.builder().refreshToken(refreshToken).build());
    }

    /**
     * 刷新第三方 OAuth2 访问令牌（基于原生 AuthToken 凭据）
     *
     * @param appKey    社交应用唯一标识
     * @param authToken 包含 refreshToken 的授权令牌对象
     * @return 刷新后的最新令牌领域模型
     */
    public SocialToken refreshToken(String appKey, AuthToken authToken) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (authToken == null) {
            throw new IllegalArgumentException("授权令牌凭据 [authToken] 不能为空");
        }

        return executeWithAppKey(appKey, () -> {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            AuthResponse<AuthToken> authResponse;
            try {
                authResponse = authRequest.refresh(authToken);
            } catch (Exception ex) {
                throw new SocialAuthException("第三方平台令牌续期异常: " + ex.getMessage(), ex);
            }

            if (authResponse == null || !authResponse.ok() || authResponse.getData() == null
                    || authResponse.getData().getAccessToken() == null || authResponse.getData().getAccessToken().isBlank()) {
                String errorMsg = authResponse != null && authResponse.getMsg() != null ? authResponse.getMsg() : "底层驱动未返回有效访问令牌";
                throw new SocialAuthException("第三方平台令牌续期失败: " + errorMsg);
            }

            log.info("Social auth token refreshed successfully for appKey: [{}]", appKey);
            return SocialToken.fromAuthToken(authResponse.getData());
        });
    }

    /**
     * 吊销第三方 OAuth2 授权凭据（基于 accessToken 字符串）
     *
     * @param appKey      社交应用唯一标识
     * @param accessToken 访问令牌
     */
    public void revokeAuth(String appKey, String accessToken) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("访问令牌 [accessToken] 不能为空");
        }
        revokeAuth(appKey, AuthToken.builder().accessToken(accessToken).build());
    }

    /**
     * 吊销第三方 OAuth2 授权凭据（基于原生 AuthToken 凭据）
     *
     * @param appKey    社交应用唯一标识
     * @param authToken 包含 accessToken 的授权令牌对象
     */
    public void revokeAuth(String appKey, AuthToken authToken) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        if (authToken == null) {
            throw new IllegalArgumentException("授权令牌凭据 [authToken] 不能为空");
        }

        executeWithAppKey(appKey, () -> {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            AuthResponse<?> authResponse;
            try {
                authResponse = authRequest.revoke(authToken);
            } catch (Exception ex) {
                throw new SocialAuthException("第三方平台授权吊销异常: " + ex.getMessage(), ex);
            }

            if (authResponse == null || !authResponse.ok()) {
                String errorMsg = authResponse != null ? authResponse.getMsg() : "底层驱动未返回响应";
                throw new SocialAuthException("第三方平台授权吊销失败: " + errorMsg);
            }

            log.info("Social auth revoked successfully for appKey: [{}]", appKey);
        });
    }

    /**
     * 获取指定社交应用的标准化配置
     *
     * @param appKey 社交应用唯一标识
     * @return 标准化应用配置
     */
    public SocialAppConfig getAppConfig(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("应用标识 [appKey] 不能为空");
        }
        return socialAppFactory.getAppConfig(appKey);
    }

    // ==================== 8. 内部编排辅助方法 ====================

    /**
     * 执行底层第三方 HTTP 换码与用户信息拉取
     */
    private SocialUser exchangeSocialUser(String appKey, SocialAppConfig appConfig, AuthCallback callback) {
        return executeWithAppKey(appKey, () -> {
            AuthRequest authRequest = socialAppFactory.createAuthRequest(appKey);
            AuthResponse<AuthUser> authResponse;
            try {
                authResponse = authRequest.login(callback);
            } catch (Exception ex) {
                throw new SocialAuthException("第三方授权认证异常: " + ex.getMessage(), ex);
            }

            if (authResponse == null || !authResponse.ok() || authResponse.getData() == null) {
                String errorMsg = authResponse != null ? authResponse.getMsg() : "第三方未返回有效响应";
                throw new SocialAuthException("第三方授权认证失败: " + errorMsg);
            }

            SocialUser socialUser = SocialUser.fromAuthUser(authResponse.getData(), appConfig.getSource());
            if (socialUser != null) {
                socialUser.setAppKey(appKey);
            }
            return socialUser;
        });
    }

    /**
     * 业务身份路由决策：已绑定用户签发会话，未绑定用户签发两阶段绑定票据
     */
    private SocialAuthResult resolveAuthResult(String appKey,
                                               SocialUser socialUser,
                                               SocialLoginOptions options,
                                               StateContext stateContext) {
        String tenantId = stateContext != null ? stateContext.getTenantId() : null;
        if (tenantId != null && !tenantId.isBlank()) {
            return TenantContextHolder.runWithTenant(tenantId, () -> doResolveAuthResult(appKey, socialUser, options, stateContext));
        }
        return doResolveAuthResult(appKey, socialUser, options, stateContext);
    }

    private SocialAuthResult doResolveAuthResult(String appKey,
                                                 SocialUser socialUser,
                                                 SocialLoginOptions options,
                                                 StateContext stateContext) {
        SocialUserDetailsService detailsService = userDetailsServiceObjectProvider.getIfAvailable();
        String userId = detailsService != null ? detailsService.loadUserIdBySocialUser(appKey, socialUser) : null;

        if (userId != null && !userId.isBlank()) {
            // 分支 1：已绑定本地系统用户 -> 自动签发受管登录会话并广播登录认证成功事件
            Object tokenInfo = sessionManager.createSession(userId, options, socialUser);
            log.info("Social user authenticated and logged in: appKey=[{}], userId=[{}], openId=[{}]",
                    appKey, userId, socialUser.getOpenId());
            publishEvent(new SocialAuthenticatedEvent(this, userId, appKey, socialUser, options, stateContext));
            return SocialAuthResult.loggedIn(userId, tokenInfo, socialUser, stateContext);
        }

        // 分支 2：未绑定本地用户，探测是否开启自动注册 (autoSignUp)
        boolean isAutoSignUp = isAutoSignUpEnabled(appKey);
        if (isAutoSignUp && detailsService != null) {
            String autoUserId = detailsService.autoRegisterUser(appKey, socialUser);
            if (autoUserId != null && !autoUserId.isBlank()) {
                log.info("Social user auto-registered successfully: appKey=[{}], userId=[{}], openId=[{}]",
                        appKey, autoUserId, socialUser.getOpenId());
                // 依次广播绑定完成与登录认证成功领域事件
                publishEvent(new SocialBoundEvent(this, autoUserId, appKey, socialUser));
                Object tokenInfo = sessionManager.createSession(autoUserId, options, socialUser);
                publishEvent(new SocialAuthenticatedEvent(this, autoUserId, appKey, socialUser, options, stateContext));
                return SocialAuthResult.loggedIn(autoUserId, tokenInfo, socialUser, stateContext);
            }
        }

        // 分支 3：未开启自动注册或自动注册返回 null -> 签发两阶段高熵绑定票据并广播票据签发事件
        Duration ticketTimeout = properties.getBindTicketTimeout() != null
                ? properties.getBindTicketTimeout()
                : BindTicketStore.DEFAULT_TICKET_TIMEOUT;
        String bindTicket = bindTicketStore.createTicket(socialUser, ticketTimeout);
        log.info("Social user not bound, issued bindTicket: appKey=[{}], openId=[{}]",
                appKey, socialUser.getOpenId());

        String maskedTicket = maskTicket(bindTicket);
        publishEvent(new SocialBindTicketIssuedEvent(this, maskedTicket, appKey, socialUser));

        return SocialAuthResult.requireBind(bindTicket, socialUser, stateContext);
    }

    private boolean isAutoSignUpEnabled(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return properties != null && properties.isAutoSignUp();
        }
        try {
            SocialAppConfig appConfig = socialAppFactory.getAppConfig(appKey);
            if (appConfig != null && appConfig.getAutoSignUp() != null) {
                return appConfig.getAutoSignUp();
            }
        } catch (Exception ex) {
            log.debug("获取应用配置失败，回退至全局 autoSignUp 配置: appKey=[{}], cause=[{}]", appKey, ex.getMessage());
        }
        return properties != null && properties.isAutoSignUp();
    }

    private static String maskTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return ticket;
        }
        int len = ticket.length();
        if (len <= 8) {
            return DesensitizeUtil.mask(ticket, 1, len - 1);
        }
        return DesensitizeUtil.mask(ticket, 4, len - 4);
    }

    /**
     * 在受管应用上下文中执行操作，确保应用标识绑定与确定性现场清理
     */
    private <T> T executeWithAppKey(String appKey, Supplier<T> action) {
        return CurrentSocialAppContextHolder.runWithAppKey(appKey, action);
    }

    /**
     * 在受管应用上下文中执行操作（无返回值版本）
     */
    private void executeWithAppKey(String appKey, Runnable action) {
        CurrentSocialAppContextHolder.runWithAppKey(appKey, action);
    }
}
