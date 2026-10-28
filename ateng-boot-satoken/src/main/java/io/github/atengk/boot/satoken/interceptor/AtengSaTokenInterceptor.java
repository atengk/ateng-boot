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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ateng-boot Sa-Token 增强路由拦截器
 * 继承原生 SaInterceptor，支持动态白名单（YAML + SPI + @Anonymous 声明式放行）；
 * 支持多账号体系动态路由与 primary-login-type 优先探测；
 * 在鉴权放行后自动将登录主体的租户 ID 与用户唯一标识注入 TenantContextHolder 与 UserContextHolder，
 * 并在 afterCompletion 强制清空四维上下文，杜绝容器线程池复用污染
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class AtengSaTokenInterceptor extends SaInterceptor {

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = UrlPathHelper.defaultInstance;
    private volatile Set<String> whitelist = Collections.emptySet();
    private final SaTokenProperties properties;
    private final ObjectProvider<DynamicPathPermissionProvider> dynamicPathPermissionProvider;
    private DynamicPathPermissionProvider customProvider;

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
            this.auth = auth;
        }
        this.properties = properties;
        this.dynamicPathPermissionProvider = dynamicPathPermissionProvider;
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
    public synchronized void setWhitelist(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            this.whitelist = Collections.emptySet();
        } else {
            this.whitelist = Collections.unmodifiableSet(new LinkedHashSet<>(paths));
        }
    }

    /**
     * 追加白名单路径集合
     *
     * @param paths 待追加的路径集合
     */
    public synchronized void addWhitelist(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(this.whitelist);
        merged.addAll(paths);
        this.whitelist = Collections.unmodifiableSet(merged);
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
            // 白名单/匿名路由：跳过 Sa-Token 鉴权（如 checkLogin），但若携带有效凭据仍同步上下文
            syncContextIfLoggedIn();
            return true;
        }

        if (this.auth == null) {
            this.auth = h -> {};
        }

        boolean pass = super.preHandle(request, response, handler);
        if (pass) {
            checkDynamicPathPermission(request, lookupPath);
            syncContextIfLoggedIn();
        }
        return pass;
    }

    /**
     * 校验动态路径权限（基于 DynamicPathPermissionProvider SPI）
     *
     * @param request    HTTP 请求
     * @param lookupPath 请求路径
     */
    protected void checkDynamicPathPermission(HttpServletRequest request, String lookupPath) {
        if (this.customProvider != null) {
            checkPermissionWithProvider(this.customProvider, request, lookupPath);
            return;
        }
        if (this.dynamicPathPermissionProvider == null) {
            return;
        }

        // 尝试单 Bean 快速获取（零额外对象分配）
        DynamicPathPermissionProvider single = null;
        try {
            single = this.dynamicPathPermissionProvider.getIfAvailable();
        } catch (Exception ignored) {
        }
        if (single != null) {
            checkPermissionWithProvider(single, request, lookupPath);
            return;
        }

        // 多 Provider 链式按序匹配
        for (DynamicPathPermissionProvider provider : this.dynamicPathPermissionProvider.orderedStream().toList()) {
            if (checkPermissionWithProvider(provider, request, lookupPath)) {
                return;
            }
        }
    }

    private boolean checkPermissionWithProvider(DynamicPathPermissionProvider provider, HttpServletRequest request, String lookupPath) {
        if (provider == null) {
            return false;
        }
        String httpMethod = request != null ? request.getMethod() : null;
        PathPermissionRule matchedRule = provider.matchRule(this.pathMatcher, lookupPath, httpMethod);
        if (matchedRule == null) {
            return false;
        }

        Set<String> requiredPermissions = matchedRule.getPermissions();
        if (requiredPermissions == null || requiredPermissions.isEmpty()) {
            return true;
        }

        // 探测当前活跃登录主体 StpLogic
        String preferredType = properties != null ? properties.getPrimaryLoginType() : null;
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        StpLogic logic = LoginHelper.getStpLogic(activeType != null ? activeType : StpUtil.TYPE);

        // 超级管理员豁免放行
        if (LoginHelper.isSuperAdmin(activeType)) {
            return true;
        }

        String[] perms = requiredPermissions.toArray(new String[0]);
        if (matchedRule.getMode() == SaMode.AND) {
            logic.checkPermissionAnd(perms);
        } else {
            logic.checkPermissionOr(perms);
        }
        return true;
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
     * 若已登录，同步用户与多租户上下文（支持多账号 primary-login-type 探测）
     */
    private void syncContextIfLoggedIn() {
        String preferredType = properties != null ? properties.getPrimaryLoginType() : null;
        String activeType = LoginHelper.getActiveLoginType(preferredType);
        if (activeType != null) {
            String tenantId = LoginHelper.getTenantId(activeType);
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContextHolder.setTenantId(tenantId);
            }
            String userId = LoginHelper.getUserId(activeType);
            if (userId != null && !userId.isBlank()) {
                UserContextHolder.set(userId);
            }
            return;
        }
        // 保底兼容：当 activeType 探测未命中时使用无参 LoginHelper 登录态（兼容 Mock 静态打桩场景）
        if (LoginHelper.isLogin()) {
            String tenantId = LoginHelper.getTenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContextHolder.setTenantId(tenantId);
            }
            String userId = LoginHelper.getUserId();
            if (userId != null && !userId.isBlank()) {
                UserContextHolder.set(userId);
            }
        }
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
