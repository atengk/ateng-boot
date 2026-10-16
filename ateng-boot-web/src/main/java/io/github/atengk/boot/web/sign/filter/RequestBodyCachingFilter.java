package io.github.atengk.boot.web.sign.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求体缓存过滤器
 * 针对非文件上传的写请求（POST/PUT/PATCH）自动将 HttpServletRequest 升级为可重读包装器
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RequestBodyCachingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String contentType = request.getContentType();
        boolean isMultipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        String method = request.getMethod();
        boolean isModifyingMethod = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        if (!isMultipart && isModifyingMethod && !(request instanceof CachedBodyHttpServletRequest)) {
            CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
            filterChain.doFilter(wrappedRequest, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
