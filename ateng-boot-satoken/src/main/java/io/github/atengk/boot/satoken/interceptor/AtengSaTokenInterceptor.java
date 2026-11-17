package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.fun.SaParamFunction;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.DynamicPathPermissionProvider;
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

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = UrlPathHelper.defaultInstance;
    private final ReentrantLock whitelistLock = new ReentrantLock();
    private volatile Set<String> whitelist = Collections.emptySet();
    private final SaTokenProperties properties;
    private final ObjectProvider<DynamicPathPermissionProvider> dynamicPathPermissionProvider;
    private DynamicPathPermissionProvider customProvider;
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
        String lookupPath = getRequestPath(request);
        if (isWhitelisted(lookupPath)) {
            // 白名单/匿名路由：跳过 Sa-Token 鉴权（如 checkLogin），若携带有效凭据仍按路由提示同步上下文
            String routeLoginType = resolveRouteLoginType(lookupPath);
            syncContextIfLoggedIn(routeLoginType, routeLoginType != null);
            return true;
        }

        // 1. 先解析动态路径权限规则与静态路由 loginType 映射（优先级：动态规则 loginType > 路由映射 > primary-login-type）
        PathPermissionRule matchedRule = resolveDynamicPathRule(request, lookupPath);
        String explicitTargetLoginType = resolveExplicitTargetLoginType(matchedRule, lookupPath);
        String checkLoginTargetType = (explicitTargetLoginType != null)
                ? explicitTargetLoginType
                : resolvePrimaryLoginType();

        // 2. 执行父类注解/自定义 auth 回调
        boolean pass = super.preHandle(request, response, handler);
        if (!pass) {
            return false;
        }

        // 3. 若未设置自定义 auth 且开启全域严格登录校验，则按解析出的目标账号体系执行 checkLogin
        String checkedLoginType = null;
        if (!this.customAuthConfigured && isCheckLoginEnabled()) {
            LoginHelper.checkLogin(checkLoginTargetType);
            checkedLoginType = checkLoginTargetType;
        }

        // 4. 校验动态路径权限规则
        if (matchedRule != null) {
            checkMatchedRulePermission(matchedRule, explicitTargetLoginType, checkedLoginType);
        }

        // 5. 将当前命中账号体系的租户与用户上下文同步至 Core 底座
        String syncPreferredType = explicitTargetLoginType != null ? explicitTargetLoginType : checkedLoginType;
        syncContextIfLoggedIn(syncPreferredType, explicitTargetLoginType != null);
        return true;
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
        if (this.customProvider != null) {
            return this.customProvider.matchRule(this.pathMatcher, lookupPath, httpMethod);
        }
        if (this.dynamicPathPermissionProvider == null) {
            return null;
        }

        DynamicPathPermissionProvider single = null;
        try {
            single = this.dynamicPathPermissionProvider.getIfAvailable();
        } catch (Exception ignored) {
        }
        if (single != null) {
            return single.matchRule(this.pathMatcher, lookupPath, httpMethod);
        }

        for (DynamicPathPermissionProvider provider : this.dynamicPathPermissionProvider.orderedStream().toList()) {
            if (provider == null) {
                continue;
            }
            PathPermissionRule matched = provider.matchRule(this.pathMatcher, lookupPath, httpMethod);
            if (matched != null) {
                return matched;
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
            String activeType = LoginHelper.getActiveLoginType(preferredType);
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
            // 无论请求成功与否，强制清空当前线程用户与多租户上下文，彻底杜绝容器线程池复用污染
            try {
                UserContextHolder.clear();
            } finally {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * 若已登录，同步用户与多租户上下文（支持多账号路由映射与 primary-login-type 探测）
     *
     * @param preferredLoginType 优先同步的账号体系类型
     * @param strictExplicitType 是否为路由/规则显式绑定的账号体系（为 true 时仅同步该体系，避免跨账号串号）
     */
    private void syncContextIfLoggedIn(String preferredLoginType, boolean strictExplicitType) {
        if (strictExplicitType && hasText(preferredLoginType)) {
            if (LoginHelper.isLogin(preferredLoginType)) {
                applyContextForLoginType(preferredLoginType);
            }
            return;
        }

        String preferredType = hasText(preferredLoginType)
                ? preferredLoginType
                : (properties != null ? properties.getPrimaryLoginType() : null);
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        if (activeType != null) {
            applyContextForLoginType(activeType);
            return;
        }
        // 保底兼容：当 activeType 探测未命中时使用无参 LoginHelper 登录态（兼容 Mock 静态打桩场景）
        if (LoginHelper.isLogin()) {
            String tenantId = LoginHelper.getTenantId();
            if (hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
            String userId = LoginHelper.getUserId();
            if (hasText(userId)) {
                UserContextHolder.set(userId);
            }
        }
    }

    private void applyContextForLoginType(String loginType) {
        String tenantId = LoginHelper.getTenantId(loginType);
        if (hasText(tenantId)) {
            TenantContextHolder.setTenantId(tenantId);
        }
        String userId = LoginHelper.getUserId(loginType);
        if (hasText(userId)) {
            UserContextHolder.set(userId);
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
