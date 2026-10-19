package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.fun.SaParamFunction;
import cn.dev33.satoken.interceptor.SaInterceptor;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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

    public AtengSaTokenInterceptor() {
        super();
    }

    public AtengSaTokenInterceptor(SaParamFunction<Object> auth) {
        super(auth);
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

        boolean pass = super.preHandle(request, response, handler);
        if (pass) {
            syncContextIfLoggedIn();
        }
        return pass;
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
     * 若已登录，同步用户与多租户上下文
     */
    private void syncContextIfLoggedIn() {
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
