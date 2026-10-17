package io.github.atengk.boot.satoken.interceptor;

import cn.dev33.satoken.interceptor.SaInterceptor;
import io.github.atengk.boot.satoken.context.TenantContextBridge;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * ateng-boot Sa-Token 增强路由拦截器
 * 继承原生 SaInterceptor，在鉴权放行后自动将登录主体的租户 ID 注入 TenantContextHolder，并在 afterCompletion 强制清空租户上下文，杜绝线程池污染
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
public class AtengSaTokenInterceptor extends SaInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        boolean pass = super.preHandle(request, response, handler);
        if (pass && LoginHelper.isLogin()) {
            String tenantId = LoginHelper.getTenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContextBridge.setTenantId(tenantId);
            }
        }
        return pass;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        try {
            super.afterCompletion(request, response, handler, ex);
        } finally {
            // 无论请求成功与否，强制清空当前线程多租户上下文，彻底杜绝容器线程池复用污染
            TenantContextBridge.clear();
        }
    }
}
