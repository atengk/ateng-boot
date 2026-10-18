package io.github.atengk.boot.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 通用请求体缓存与防爆过滤器
 * 针对非文件上传且非普通表单的写请求（POST/PUT/PATCH）自动将 HttpServletRequest 升级为可重复读取的包装器
 * 内置排除表单提交以防 Servlet 容器参数解析失效，并支持请求体最大载荷上限防御
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class ContentCachingFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 默认过滤器执行优先级：紧跟 TraceFilter 之后，早于业务切面与下游日志过滤器
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final int maxBodySize;

    public ContentCachingFilter() {
        this(CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_SIZE);
    }

    public ContentCachingFilter(int maxBodySize) {
        this.maxBodySize = maxBodySize > 0 ? maxBodySize : CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_SIZE;
    }

    public ContentCachingFilter(DataSize maxBodySize) {
        this(maxBodySize != null ? (int) maxBodySize.toBytes() : CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_SIZE);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 获取请求体最大缓存字节数上限
     *
     * @return 最大字节数
     */
    public int getMaxBodySize() {
        return maxBodySize;
    }

    /**
     * 将原始请求升级包装为可重复读请求包装器
     *
     * @param request 原始 HTTP 请求
     * @return 可重读包装器
     * @throws IOException 发生 IO 读取异常时抛出
     */
    protected HttpServletRequest wrapRequest(HttpServletRequest request) throws IOException {
        return new CachedBodyHttpServletRequest(request, this.maxBodySize);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String contentType = request.getContentType();
        boolean isMultipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        boolean isForm = contentType != null && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded");
        String method = request.getMethod();
        boolean isModifyingMethod = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        if (!isMultipart && !isForm && isModifyingMethod && !(request instanceof CachedBodyHttpServletRequest)) {
            HttpServletRequest wrappedRequest = wrapRequest(request);
            filterChain.doFilter(wrappedRequest, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
