package io.github.atengk.boot.web.sign.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.unit.DataSize;

import java.io.IOException;

/**
 * 可重复读取请求体的 HttpServletRequest 包装器
 *
 * @author Ateng
 * @since 2026-10-16
 * @deprecated 请使用 {@link io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest}，本类仅保留用于向后兼容
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public class CachedBodyHttpServletRequest extends io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest {

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
    }

    public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBodySize) throws IOException {
        super(request, maxBodySize);
    }

    public CachedBodyHttpServletRequest(HttpServletRequest request, DataSize maxBodySize) throws IOException {
        super(request, maxBodySize);
    }
}
