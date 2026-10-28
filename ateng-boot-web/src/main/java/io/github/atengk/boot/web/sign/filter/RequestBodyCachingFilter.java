package io.github.atengk.boot.web.sign.filter;

import io.github.atengk.boot.web.filter.ContentCachingFilter;
import org.springframework.util.unit.DataSize;

/**
 * 请求体缓存过滤器
 *
 * @author Ateng
 * @since 2026-10-16
 * @deprecated 请使用 {@link io.github.atengk.boot.web.filter.ContentCachingFilter}，本类仅保留用于向后兼容
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public class RequestBodyCachingFilter extends ContentCachingFilter {

    public RequestBodyCachingFilter() {
        super();
    }

    public RequestBodyCachingFilter(int maxBodySize) {
        super(maxBodySize);
    }

    public RequestBodyCachingFilter(DataSize maxBodySize) {
        super(maxBodySize);
    }

    @Override
    protected jakarta.servlet.http.HttpServletRequest wrapRequest(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        return new CachedBodyHttpServletRequest(request, getMaxBodySize());
    }
}
