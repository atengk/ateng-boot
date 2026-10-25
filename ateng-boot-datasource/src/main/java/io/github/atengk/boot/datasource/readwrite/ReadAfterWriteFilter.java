package io.github.atengk.boot.datasource.readwrite;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

/**
 * 请求作用域写后读主上下文清理过滤器
 * 确保在 Servlet HTTP 请求生命周期结束时，确定性清除当前工作线程的写标记，
 * 严密防御池化线程复用导致的写后读主串读与上下文污染。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class ReadAfterWriteFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            ReadAfterWriteContextHolder.clear();
        }
    }
}
