package io.github.atengk.boot.web.trace;

import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链路追踪过滤器 TraceFilter 单元测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("链路追踪过滤器 TraceFilter 测试")
class TraceFilterTest {

    private WebProperties properties;
    private TraceFilter traceFilter;

    @BeforeEach
    void setUp() {
        MDC.clear();
        properties = new WebProperties();
        traceFilter = new TraceFilter(properties);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("验证 TraceFilter 默认优先级为最高优先级 HIGHEST_PRECEDENCE")
    void shouldHaveHighestPrecedenceOrder() {
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, traceFilter.getOrder());
    }

    @Test
    @DisplayName("当请求包含 X-Trace-Id 请求头时直接透传并回写响应头")
    void shouldExtractTraceIdFromHeaderAndEchoToResponse() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Trace-Id", "trace-upstream-12345");

        AtomicReference<String> mdcTraceIdDuringExecution = new AtomicReference<>();
        FilterChain filterChain = (req, res) -> mdcTraceIdDuringExecution.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));

        traceFilter.doFilter(request, response, filterChain);

        // 验证执行过程中 MDC 注入了请求头传入的 traceId
        assertEquals("trace-upstream-12345", mdcTraceIdDuringExecution.get());
        // 验证响应头回显
        assertEquals("trace-upstream-12345", response.getHeader("X-Trace-Id"));
        // 验证执行完毕后 MDC 已被严格清理
        assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
    }

    @Test
    @DisplayName("当请求未带 TraceId 时自动生成 32 位无横杠 UUID")
    void shouldGenerateTraceIdWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        FilterChain filterChain = (req, res) -> mdcTraceId.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));

        traceFilter.doFilter(request, response, filterChain);

        String generatedTraceId = mdcTraceId.get();
        assertNotNull(generatedTraceId);
        assertEquals(32, generatedTraceId.length());
        assertFalse(generatedTraceId.contains("-"));

        assertEquals(generatedTraceId, response.getHeader("X-Trace-Id"));
        assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
    }

    @Test
    @DisplayName("支持自定义追踪请求头配置")
    void shouldSupportCustomTraceHeader() throws ServletException, IOException {
        properties.getTrace().setHeader("X-Custom-Trace-Id");
        TraceFilter customTraceFilter = new TraceFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Custom-Trace-Id", "custom-trace-888");

        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        FilterChain filterChain = (req, res) -> mdcTraceId.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));

        customTraceFilter.doFilter(request, response, filterChain);

        assertEquals("custom-trace-888", mdcTraceId.get());
        assertEquals("custom-trace-888", response.getHeader("X-Custom-Trace-Id"));
        assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
    }
}
