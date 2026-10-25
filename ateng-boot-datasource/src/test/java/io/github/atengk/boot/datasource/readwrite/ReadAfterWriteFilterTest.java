package io.github.atengk.boot.datasource.readwrite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 写后读主请求作用域上下文清理过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class ReadAfterWriteFilterTest {

    @AfterEach
    void tearDown() {
        ReadAfterWriteContextHolder.clear();
    }

    @Test
    @DisplayName("验证请求生命周期结束时 Filter 在 finally 块中确定性清除写标记")
    void shouldClearReadAfterWriteContextAfterFilterExecution() throws ServletException, IOException {
        ReadAfterWriteFilter filter = new ReadAfterWriteFilter();
        ServletRequest request = mock(ServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        doAnswer(invocation -> {
            ReadAfterWriteContextHolder.markWriteOccurred();
            assertThat(ReadAfterWriteContextHolder.isWriteOccurred()).isTrue();
            return null;
        }).when(chain).doFilter(request, response);

        assertThat(ReadAfterWriteContextHolder.isWriteOccurred()).isFalse();
        filter.doFilter(request, response, chain);

        // 验证请求完成后被可靠清理
        assertThat(ReadAfterWriteContextHolder.isWriteOccurred()).isFalse();
    }
}
