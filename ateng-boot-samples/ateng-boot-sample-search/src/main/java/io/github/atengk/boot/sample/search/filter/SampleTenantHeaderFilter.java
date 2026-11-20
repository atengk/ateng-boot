package io.github.atengk.boot.sample.search.filter;

import io.github.atengk.boot.core.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 样例工程专属 HTTP 请求头多租户上下文透传过滤器
 * <p>
 * 仅在 {@code ateng-boot-sample-search} 工程内部生效，负责从 {@code X-Tenant-Id} 请求头提取租户标识
 * 并绑定至 {@link TenantContextHolder}，请求处理完毕后在 {@code finally} 中确定性还原/清理线程上下文。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SampleTenantHeaderFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContextHolder.TenantSnapshot previous = TenantContextHolder.capturePrevious();
        String tenantHeader = request.getHeader(TENANT_HEADER);
        try {
            TenantContextHolder.clear();
            if (StringUtils.hasText(tenantHeader)) {
                TenantContextHolder.setTenantId(tenantHeader.trim());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.restore(previous);
        }
    }
}
