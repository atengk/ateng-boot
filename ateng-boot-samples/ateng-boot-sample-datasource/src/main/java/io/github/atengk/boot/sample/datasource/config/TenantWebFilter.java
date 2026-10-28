package io.github.atengk.boot.sample.datasource.config;

import io.github.atengk.boot.core.context.TenantContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * HTTP 请求租户上下文拦截过滤器
 * <p>
 * 从入站请求标头 X-Tenant-Id 提取租户标识并注入 TenantContextHolder，并在请求完成时清理。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantWebFilter implements Filter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest request) {
            String tenantId = request.getHeader(TENANT_HEADER);
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
        }
        try {
            chain.doFilter(servletRequest, servletResponse);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
