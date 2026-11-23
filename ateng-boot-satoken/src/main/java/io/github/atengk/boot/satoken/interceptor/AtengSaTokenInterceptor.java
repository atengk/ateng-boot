package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.fun.SaParamFunction;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.DataPermissionBridge;
import io.github.atengk.boot.satoken.spi.DynamicPathPermissionProvider;
import io.github.atengk.boot.satoken.spi.MyBatisPlusDataPermissionBridge;
import io.github.atengk.boot.satoken.spi.TenantHeaderSwitchValidator;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.util.UrlPathHelper;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * ateng-boot Sa-Token 增强路由拦截器
 * 继承原生 SaInterceptor，支持动态白名单（YAML + SPI + @Anonymous 声明式放行）；
 * 支持 check-login=true 严格模式下的多账号体系路由映射（login-type-routes 最精确匹配优先）与 primary-login-type 兜底；
 * 在鉴权放行后自动将当前命中账号体系的租户 ID 与用户唯一标识注入 TenantContextHolder 与 UserContextHolder，
 * 并在 afterCompletion 强制清空四维上下文，杜绝容器线程池复用污染
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class AtengSaTokenInterceptor extends SaInterceptor {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = UrlPathHelper.defaultInstance;
    private final ReentrantLock whitelistLock = new ReentrantLock();
    private volatile Set<String> whitelist = Collections.emptySet();
    private final SaTokenProperties properties;
    private final ObjectProvider<DynamicPathPermissionProvider> dynamicPathPermissionProvider;
    private DynamicPathPermissionProvider customProvider;
    private volatile DataPermissionBridge dataPermissionBridge = MyBatisPlusDataPermissionBridge.getInstance();
    private volatile TenantHeaderSwitchValidator tenantHeaderSwitchValidator;
    private volatile boolean customAuthConfigured;

    public AtengSaTokenInterceptor() {
        this((SaTokenProperties) null);
    }

    public AtengSaTokenInterceptor(SaParamFunction<Object> auth) {
        this(auth, null);
    }

    public AtengSaTokenInterceptor(SaTokenProperties properties) {
        this(null, properties, null);
    }

    public AtengSaTokenInterceptor(SaParamFunction<Object> auth, SaTokenProperties properties) {
        this(auth, properties, null);
    }

    public AtengSaTokenInterceptor(SaTokenProperties properties, ObjectProvider<DynamicPathPermissionProvider> dynamicPathPermissionProvider) {
        this(null, properties, dynamicPathPermissionProvider);
    }

    public AtengSaTokenInterceptor(SaParamFunction<Object> auth, SaTokenProperties properties, ObjectProvider<DynamicPathPermissionProvider> dynamicPathPermissionProvider) {
        super();
        if (auth != null) {
            super.setAuth(auth);
            this.customAuthConfigured = true;
        }
        this.properties = properties;
        this.dynamicPathPermissionProvider = dynamicPathPermissionProvider;
    }

    @Override
    public SaInterceptor setAuth(SaParamFunction<Object> auth) {
        this.customAuthConfigured = (auth != null);
        return super.setAuth(auth != null ? auth : handler -> {});
    }

    /**
     * 判断当前拦截器是否已显式配置外部自定义 auth 回调
     *
     * @return true 表示已显式配置自定义 auth 回调
     */
    public boolean isCustomAuthConfigured() {
        return this.customAuthConfigured;
    }

    /**
     * 手动编程式设置动态路径权限提供者（便于单测与静态定制）
     *
     * @param customProvider 动态路径权限提供者
     */
    public void setDynamicPathPermissionProvider(DynamicPathPermissionProvider customProvider) {
        this.customProvider = customProvider;
    }

    /**
     * 设置数据权限超管上下文桥接器
     *
     * @param dataPermissionBridge 数据权限桥接器（为 null 时回退默认弱依赖桥接器）
     */
    public void setDataPermissionBridge(DataPermissionBridge dataPermissionBridge) {
        this.dataPermissionBridge = dataPermissionBridge != null
                ? dataPermissionBridge
                : MyBatisPlusDataPermissionBridge.getInstance();
    }

    /**
     * 设置请求头租户切换与匿名解析校验器 SPI
     *
     * @param tenantHeaderSwitchValidator 租户切换校验器
     */
    public void setTenantHeaderSwitchValidator(TenantHeaderSwitchValidator tenantHeaderSwitchValidator) {
        this.tenantHeaderSwitchValidator = tenantHeaderSwitchValidator;
    }

    /**
     * 设置全量白名单路径集合（覆盖并去重）
     *
     * @param paths 白名单路径集合
     */
    public void setWhitelist(Collection<String> paths) {
        whitelistLock.lock();
        try {
            if (paths == null || paths.isEmpty()) {
                this.whitelist = Collections.emptySet();
            } else {
                this.whitelist = Collections.unmodifiableSet(new LinkedHashSet<>(paths));
            }
        } finally {
            if (whitelistLock.isHeldByCurrentThread()) {
                whitelistLock.unlock();
            }
        }
    }

    /**
     * 追加白名单路径集合
     *
     * @param paths 待追加的路径集合
     */
    public void addWhitelist(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        whitelistLock.lock();
        try {
            Set<String> merged = new LinkedHashSet<>(this.whitelist);
            merged.addAll(paths);
            this.whitelist = Collections.unmodifiableSet(merged);
        } finally {
            if (whitelistLock.isHeldByCurrentThread()) {
                whitelistLock.unlock();
            }
        }
    }

    /**
     * 获取当前生效的不可变白名单路径集合
     *
     * @return 白名单路径集合
     */
    public Set<String> getWhitelist() {
        return this.whitelist;
    }

    /**
     * 判断指定请求路径是否命中白名单
     *
     * @param lookupPath 请求路径
     * @return true 表示命中白名单直接放行
     */
    public boolean isWhitelisted(String lookupPath) {
        if (lookupPath == null || lookupPath.isBlank() || this.whitelist.isEmpty()) {
            return false;
        }
        // 1. 精确匹配快速通道 (O(1) 短路)
        if (this.whitelist.contains(lookupPath)) {
            return true;
        }
        // 2. 通配符模式匹配
        for (String pattern : this.whitelist) {
            if (pathMatcher.match(pattern, lookupPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 前置重置路由上下文与超管数据权限上下文，防止工作线程残留上一请求的脏状态
        SaTokenRouteContextHolder.clear();
        resetDataPermissionSuperAdmin();
        try {
            String lookupPath = getRequestPath(request);
            if (isWhitelisted(lookupPath)) {
                // 白名单/匿名路由：先写入命中的路由 loginType 上下文，再跳过 Sa-Token 鉴权并按需同步上下文
                String routeLoginType = resolveRouteLoginType(lookupPath);
                if (routeLoginType != null) {
                    SaTokenRouteContextHolder.set(routeLoginType);
                }
                boolean loggedIn = syncContextIfLoggedIn(request, routeLoginType, routeLoginType != null);
                if (!loggedIn) {
                    applyAnonymousTenantHeaderIfAllowed(request, routeLoginType);
                }
                return true;
            }

            // 1. 先解析路径权限规则（DynamicPathPermissionProvider SPI 优先，未命中回退 YAML path-permissions）与路由 loginType
            PathPermissionRule matchedRule = resolveDynamicPathRule(request, lookupPath);
            String explicitTargetLoginType = resolveExplicitTargetLoginType(matchedRule, lookupPath);
            String routedOrDetectedLoginType = explicitTargetLoginType;
            if (routedOrDetectedLoginType == null && (isSharedLoginTypePath(lookupPath) || matchedRule != null)) {
                routedOrDetectedLoginType = resolveActiveLoginTypeCandidate(resolvePrimaryLoginType());
            }
            if (routedOrDetectedLoginType != null) {
                SaTokenRouteContextHolder.set(routedOrDetectedLoginType);
            }
            String checkLoginTargetType = (routedOrDetectedLoginType != null)
                    ? routedOrDetectedLoginType
                    : resolvePrimaryLoginType();

            // 2. 执行父类注解/自定义 auth 回调
            boolean pass = super.preHandle(request, response, handler);
            if (!pass) {
                clearAllRequestContexts();
                return false;
            }

            // 3. 若未设置自定义 auth 且开启全域严格登录校验，则按解析出的目标账号体系执行 checkLogin
            String checkedLoginType = null;
            if (!this.customAuthConfigured && isCheckLoginEnabled()) {
                LoginHelper.checkLogin(checkLoginTargetType);
                checkedLoginType = checkLoginTargetType;
            }

            // 4. 校验路径权限规则（动态 SPI 或 YAML path-permissions）
            if (matchedRule != null) {
                checkMatchedRulePermission(matchedRule, routedOrDetectedLoginType, checkedLoginType);
            }

            // 5. 将当前命中账号体系的租户、用户与数据权限超管上下文同步至底座
            String syncPreferredType = routedOrDetectedLoginType != null ? routedOrDetectedLoginType : checkedLoginType;
            boolean loggedIn = syncContextIfLoggedIn(request, syncPreferredType, explicitTargetLoginType != null);
            if (!loggedIn) {
                applyAnonymousTenantHeaderIfAllowed(request, explicitTargetLoginType);
            }
            return true;
        } catch (Throwable ex) {
            clearAllRequestContexts();
            throw ex;
        }
    }

    /**
     * 按 {@code interceptor.login-type-routes} 解析当前请求路径对应的账号体系标识
     * <p>
     * 存在重叠路径模式时，使用 {@link AntPathMatcher#getPatternComparator(String)} 选取最精确模式；
     * 精度相同时遵循配置声明顺序（先声明者优先）。
     * </p>
     *
     * @param lookupPath 请求路径
     * @return 命中的 loginType；未命中时返回 null
     */
    public String resolveRouteLoginType(String lookupPath) {
        if (!hasText(lookupPath) || properties == null || properties.getInterceptor() == null) {
            return null;
        }
        Map<String, List<String>> routes = properties.getInterceptor().getLoginTypeRoutes();
        if (routes == null || routes.isEmpty()) {
            return null;
        }

        Comparator<String> comparator = pathMatcher.getPatternComparator(lookupPath);
        String bestPattern = null;
        String bestLoginType = null;

        for (Map.Entry<String, List<String>> entry : routes.entrySet()) {
            String loginType = entry.getKey();
            List<String> patterns = entry.getValue();
            if (!hasText(loginType) || patterns == null || patterns.isEmpty()) {
                continue;
            }
            String normalizedLoginType = loginType.trim();
            for (String rawPattern : patterns) {
                String pattern = normalizeRoutePattern(rawPattern);
                if (pattern == null || !pathMatcher.match(pattern, lookupPath)) {
                    continue;
                }
                if (bestPattern == null || comparator.compare(pattern, bestPattern) < 0) {
                    bestPattern = pattern;
                    bestLoginType = normalizedLoginType;
                }
            }
        }
        return bestLoginType;
    }

    /**
     * 校验动态路径权限（基于 DynamicPathPermissionProvider SPI）
     *
     * @param request    HTTP 请求
     * @param lookupPath 请求路径
     */
    protected void checkDynamicPathPermission(HttpServletRequest request, String lookupPath) {
        PathPermissionRule matchedRule = resolveDynamicPathRule(request, lookupPath);
        if (matchedRule != null) {
            checkMatchedRulePermission(matchedRule, resolveExplicitTargetLoginType(matchedRule, lookupPath), null);
        }
    }

    private String resolveExplicitTargetLoginType(PathPermissionRule matchedRule, String lookupPath) {
        if (matchedRule != null && hasText(matchedRule.getLoginType())) {
            return matchedRule.getLoginType().trim();
        }
        return resolveRouteLoginType(lookupPath);
    }

    private PathPermissionRule resolveDynamicPathRule(HttpServletRequest request, String lookupPath) {
        String httpMethod = request != null ? request.getMethod() : null;
        // 1. 优先匹配外部编程式或 Spring 容器注册的 DynamicPathPermissionProvider SPI
        if (this.customProvider != null) {
            PathPermissionRule matched = this.customProvider.matchRule(this.pathMatcher, lookupPath, httpMethod);
            if (matched != null) {
                return matched;
            }
        }
        if (this.dynamicPathPermissionProvider != null) {
            DynamicPathPermissionProvider single = null;
            try {
                single = this.dynamicPathPermissionProvider.getIfAvailable();
            } catch (Exception ignored) {
            }
            if (single != null) {
                PathPermissionRule matched = single.matchRule(this.pathMatcher, lookupPath, httpMethod);
                if (matched != null) {
                    return matched;
                }
            } else {
                for (DynamicPathPermissionProvider provider : this.dynamicPathPermissionProvider.orderedStream().toList()) {
                    if (provider == null) {
                        continue;
                    }
                    PathPermissionRule matched = provider.matchRule(this.pathMatcher, lookupPath, httpMethod);
                    if (matched != null) {
                        return matched;
                    }
                }
            }
        }
        // 2. 动态规则未命中时，回退匹配 YAML 静态路径权限规则 (interceptor.path-permissions)
        return resolveStaticPathPermissionRule(lookupPath, httpMethod);
    }

    private PathPermissionRule resolveStaticPathPermissionRule(String lookupPath, String httpMethod) {
        if (properties == null || properties.getInterceptor() == null) {
            return null;
        }
        List<PathPermissionRule> staticRules = properties.getInterceptor().getPathPermissions();
        if (staticRules == null || staticRules.isEmpty()) {
            return null;
        }
        for (PathPermissionRule rule : staticRules) {
            if (rule != null && rule.matches(this.pathMatcher, lookupPath, httpMethod)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 判断指定路径是否命中多账号共享路径模式列表 (interceptor.shared-login-type-paths)
     *
     * @param lookupPath 请求路径
     * @return true 表示属于多账号共享路径
     */
    public boolean isSharedLoginTypePath(String lookupPath) {
        if (!hasText(lookupPath) || properties == null || properties.getInterceptor() == null) {
            return false;
        }
        List<String> sharedPaths = properties.getInterceptor().getSharedLoginTypePaths();
        if (sharedPaths == null || sharedPaths.isEmpty()) {
            return false;
        }
        for (String rawPattern : sharedPaths) {
            String pattern = normalizeRoutePattern(rawPattern);
            if (pattern != null && pathMatcher.match(pattern, lookupPath)) {
                return true;
            }
        }
        return false;
    }

    private String resolveActiveLoginTypeCandidate(String preferredType) {
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        if (hasText(activeType)) {
            return activeType.trim();
        }
        if (hasText(preferredType) && LoginHelper.isLogin(preferredType.trim())) {
            return preferredType.trim();
        }
        if (properties == null || properties.getInterceptor() == null) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>();
        Map<String, List<String>> routes = properties.getInterceptor().getLoginTypeRoutes();
        if (routes != null) {
            for (String routeType : routes.keySet()) {
                if (hasText(routeType)) {
                    candidates.add(routeType.trim());
                }
            }
        }
        List<PathPermissionRule> staticRules = properties.getInterceptor().getPathPermissions();
        if (staticRules != null) {
            for (PathPermissionRule rule : staticRules) {
                if (rule != null && hasText(rule.getLoginType())) {
                    candidates.add(rule.getLoginType().trim());
                }
            }
        }
        for (String candidate : candidates) {
            if (LoginHelper.isLogin(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void checkMatchedRulePermission(
            PathPermissionRule matchedRule,
            String explicitTargetLoginType,
            String alreadyCheckedLoginType) {
        String ruleLoginType = hasText(matchedRule.getLoginType()) ? matchedRule.getLoginType().trim() : null;
        Set<String> requiredPermissions = matchedRule.getPermissions();
        boolean hasPermissions = requiredPermissions != null && !requiredPermissions.isEmpty();
        if (ruleLoginType == null && !hasPermissions) {
            return;
        }

        // 探测目标账号体系 StpLogic（动态规则/路由显式绑定 loginType 优先，否则自动探测当前活跃账号体系）
        String targetType = explicitTargetLoginType;
        if (targetType == null) {
            String preferredType = properties != null ? properties.getPrimaryLoginType() : null;
            String activeType = resolveActiveLoginTypeCandidate(preferredType);
            targetType = hasText(activeType) ? activeType : StpUtil.TYPE;
        }
        StpLogic logic = LoginHelper.getStpLogic(targetType);

        // 先校验目标账号体系的登录态（若已在 check-login 阶段校验同一体系则避免重复调用）
        if (!targetType.equals(alreadyCheckedLoginType)) {
            logic.checkLogin();
        }
        if (!hasPermissions) {
            return;
        }

        // 超级管理员豁免放行
        if (LoginHelper.isSuperAdmin(targetType)) {
            return;
        }

        String[] perms = requiredPermissions.toArray(new String[0]);
        if (matchedRule.getMode() == SaMode.AND) {
            logic.checkPermissionAnd(perms);
        } else {
            logic.checkPermissionOr(perms);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        try {
            super.afterCompletion(request, response, handler, ex);
        } finally {
            // 无论请求成功与否，强制清空当前线程用户、多租户及数据权限上下文，彻底杜绝容器线程池复用污染
            clearAllRequestContexts();
        }
    }

    private void resetDataPermissionSuperAdmin() {
        DataPermissionBridge bridge = this.dataPermissionBridge;
        if (bridge != null) {
            bridge.setSuperAdmin(false);
        }
    }

    private void clearAllRequestContexts() {
        try {
            SaTokenRouteContextHolder.clear();
        } finally {
            try {
                UserContextHolder.clear();
            } finally {
                try {
                    TenantContextHolder.clear();
                } finally {
                    DataPermissionBridge bridge = this.dataPermissionBridge;
                    if (bridge != null) {
                        bridge.clear();
                    }
                }
            }
        }
    }

    /**
     * 若已登录，同步用户、多租户与数据权限超管上下文（支持多账号路由映射与 primary-login-type 探测）
     *
     * @param request            HTTP 请求（用于提取受控租户切换头）
     * @param preferredLoginType 优先同步的账号体系类型
     * @param strictExplicitType 是否为路由/规则显式绑定的账号体系（为 true 时仅同步该体系，避免跨账号串号）
     * @return true 表示当前请求已处于登录态并完成上下文同步；false 表示当前未登录
     */
    private boolean syncContextIfLoggedIn(HttpServletRequest request, String preferredLoginType, boolean strictExplicitType) {
        if (strictExplicitType && hasText(preferredLoginType)) {
            if (LoginHelper.isLogin(preferredLoginType)) {
                applyContextForLoginType(request, preferredLoginType);
                return true;
            }
            return false;
        }

        String preferredType = hasText(preferredLoginType)
                ? preferredLoginType
                : (properties != null ? properties.getPrimaryLoginType() : null);
        String activeType = resolveActiveLoginTypeCandidate(preferredType);
        if (activeType != null) {
            applyContextForLoginType(request, activeType);
            return true;
        }
        // 保底兼容：当 activeType 探测未命中时使用无参 LoginHelper 登录态（兼容 Mock 静态打桩场景）
        if (LoginHelper.isLogin()) {
            applyResolvedContext(request, StpUtil.TYPE, LoginHelper.getUserId(), LoginHelper.getTenantId(), LoginHelper.isSuperAdmin());
            return true;
        }
        return false;
    }

    private void applyContextForLoginType(HttpServletRequest request, String loginType) {
        applyResolvedContext(
                request,
                loginType,
                LoginHelper.getUserId(loginType),
                LoginHelper.getTenantId(loginType),
                LoginHelper.isSuperAdmin(loginType)
        );
    }

    private void applyResolvedContext(
            HttpServletRequest request,
            String loginType,
            String userId,
            String sessionTenantId,
            boolean superAdmin) {
        String effectiveTenantId = resolveEffectiveTenantIdForLoggedInUser(request, sessionTenantId, loginType, superAdmin);
        if (hasText(effectiveTenantId)) {
            TenantContextHolder.setTenantId(effectiveTenantId);
        }
        if (hasText(userId)) {
            UserContextHolder.set(userId);
        }
        syncDataPermissionSuperAdmin(superAdmin);
    }

    private void applyAnonymousTenantHeaderIfAllowed(HttpServletRequest request, String routeLoginType) {
        SaTokenProperties.TenantProperties tenantProps = resolveTenantProperties();
        if (tenantProps == null || !tenantProps.isAllowAnonymousHeader()) {
            return;
        }
        String headerTenantId = extractTenantHeader(request, tenantProps);
        if (headerTenantId == null) {
            return;
        }
        // 防跨端绕过：即使目标路由对应 loginType 未登录，若请求携带了其他任意账号体系的有效 Token，
        // 亦不得将其视为匿名请求而直接采信客户端伪造的 X-Tenant-Id
        if (hasAnyActiveLoginSession()) {
            log.debug("检测到请求已携带有效登录会话，拒绝按匿名规则解析租户请求头: routeLoginType={}", routeLoginType);
            return;
        }
        validateTenantHeaderFormatAndSwitch(headerTenantId, null, routeLoginType, true);
        TenantContextHolder.setTenantId(headerTenantId);
    }

    private boolean hasAnyActiveLoginSession() {
        return resolveActiveLoginTypeCandidate(resolvePrimaryLoginType()) != null || LoginHelper.isLogin();
    }

    private String resolveEffectiveTenantIdForLoggedInUser(
            HttpServletRequest request,
            String sessionTenantId,
            String loginType,
            boolean superAdmin) {
        if (!superAdmin) {
            // 普通已登录用户绝对忽略请求头租户参数，强制锁定会话归属租户
            return sessionTenantId;
        }
        SaTokenProperties.TenantProperties tenantProps = resolveTenantProperties();
        if (tenantProps == null || !tenantProps.isAllowSuperAdminSwitch()) {
            return sessionTenantId;
        }
        if (!isPlatformTenantAllowed(tenantProps, sessionTenantId)) {
            return sessionTenantId;
        }
        String headerTenantId = extractTenantHeader(request, tenantProps);
        if (headerTenantId == null) {
            return sessionTenantId;
        }
        validateTenantHeaderFormatAndSwitch(headerTenantId, sessionTenantId, loginType, false);
        return headerTenantId;
    }

    private SaTokenProperties.TenantProperties resolveTenantProperties() {
        return properties != null ? properties.getTenant() : null;
    }

    private String extractTenantHeader(HttpServletRequest request, SaTokenProperties.TenantProperties tenantProps) {
        if (request == null || tenantProps == null || !hasText(tenantProps.getHeaderName())) {
            return null;
        }
        String raw = request.getHeader(tenantProps.getHeaderName().trim());
        if (!hasText(raw)) {
            return null;
        }
        return raw.trim();
    }

    private boolean isPlatformTenantAllowed(SaTokenProperties.TenantProperties tenantProps, String sessionTenantId) {
        List<String> platformTenantIds = tenantProps.getPlatformTenantIds();
        if (platformTenantIds == null || platformTenantIds.isEmpty()) {
            return true;
        }
        boolean hasConfiguredPlatformTenant = false;
        String normalizedSessionTenant = hasText(sessionTenantId) ? sessionTenantId.trim() : null;
        for (String candidate : platformTenantIds) {
            if (!hasText(candidate)) {
                continue;
            }
            hasConfiguredPlatformTenant = true;
            if (normalizedSessionTenant != null && candidate.trim().equals(normalizedSessionTenant)) {
                return true;
            }
        }
        return !hasConfiguredPlatformTenant;
    }

    private void validateTenantHeaderFormatAndSwitch(
            String targetTenantId,
            String currentTenantId,
            String loginType,
            boolean anonymous) {
        if (!isValidTenantIdFormat(targetTenantId)) {
            log.warn("拦截非法格式的租户请求头: targetTenantId={}, loginType={}, anonymous={}",
                    targetTenantId, loginType, anonymous);
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法租户标识格式");
        }
        TenantHeaderSwitchValidator validator = this.tenantHeaderSwitchValidator;
        if (validator != null && !validator.validate(targetTenantId, currentTenantId, loginType, anonymous)) {
            log.warn("租户请求头未通过切换校验 SPI: targetTenantId={}, currentTenantId={}, loginType={}, anonymous={}",
                    targetTenantId, currentTenantId, loginType, anonymous);
            throw new BusinessException(CommonResultCode.FORBIDDEN, "无权切换或访问目标租户");
        }
    }

    private static boolean isValidTenantIdFormat(String tenantId) {
        if (!hasText(tenantId)) {
            return false;
        }
        if (tenantId.contains("..") || tenantId.endsWith(".")) {
            return false;
        }
        return TENANT_ID_PATTERN.matcher(tenantId).matches();
    }

    private void syncDataPermissionSuperAdmin(boolean superAdmin) {
        DataPermissionBridge bridge = this.dataPermissionBridge;
        if (bridge != null) {
            bridge.setSuperAdmin(superAdmin);
        }
    }

    private boolean isCheckLoginEnabled() {
        return properties != null
                && properties.getInterceptor() != null
                && properties.getInterceptor().isCheckLogin();
    }

    private String resolvePrimaryLoginType() {
        if (properties != null && hasText(properties.getPrimaryLoginType())) {
            return properties.getPrimaryLoginType().trim();
        }
        return StpUtil.TYPE;
    }

    private static String normalizeRoutePattern(String rawPattern) {
        if (!hasText(rawPattern)) {
            return null;
        }
        String trimmed = rawPattern.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * 安全提取请求路径，防御 mock 或特殊容器环境下的 NPE 异常
     *
     * @param request HTTP 请求
     * @return 路径字符串
     */
    protected String getRequestPath(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        try {
            String uri = request.getRequestURI();
            if (uri == null || uri.isBlank()) {
                return "";
            }
            return urlPathHelper.getLookupPathForRequest(request);
        } catch (Exception e) {
            String uri = request.getRequestURI();
            return uri != null ? uri : "";
        }
    }
}
