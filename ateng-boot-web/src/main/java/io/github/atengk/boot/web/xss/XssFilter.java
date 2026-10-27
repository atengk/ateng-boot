package io.github.atengk.boot.web.xss;

import io.github.atengk.boot.web.filter.SecurityHeadersFilter;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * XSS 跨站脚本安全清洗过滤器
 * 拦截入站 HTTP 请求，匹配白名单后使用 {@link XssHttpServletRequestWrapper} 包装请求以无害化清洗 Query 与 Form 表单参数
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class XssFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 请求上下文属性标记：指示当前请求命中了 XSS 排除白名单，后续反序列化器应跳过清洗
     */
    public static final String XSS_SKIP_ATTRIBUTE = XssFilter.class.getName() + ".SKIP";

    /**
     * 过滤器执行优先级：置于安全响应头与 ETag 之后，保证进入业务逻辑前完成请求包装
     */
    public static final int ORDER = SecurityHeadersFilter.ORDER + 2;

    private final WebProperties.XssProperties properties;
    private final XssCleaner cleaner;

    public XssFilter(WebProperties.XssProperties properties, XssCleaner cleaner) {
        this.properties = properties != null ? properties : new WebProperties.XssProperties();
        this.cleaner = cleaner != null ? cleaner : new XssCleaner(this.properties.getMode());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isExcluded(request)) {
            // 标记当前请求为豁免状态，通知下游 Jackson 反序列化器跳过清洗
            request.setAttribute(XSS_SKIP_ATTRIBUTE, Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        XssHttpServletRequestWrapper wrappedRequest = new XssHttpServletRequestWrapper(request, cleaner);
        filterChain.doFilter(wrappedRequest, response);
    }

    private boolean isExcluded(HttpServletRequest request) {
        return ServletUtil.matchesAnyPath(request, properties.getExcludeUrls());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    public WebProperties.XssProperties getProperties() {
        return properties;
    }

    public XssCleaner getCleaner() {
        return cleaner;
    }
}
