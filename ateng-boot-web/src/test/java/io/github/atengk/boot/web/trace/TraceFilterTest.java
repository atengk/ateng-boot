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

    @Test
    @DisplayName("精确统计在途请求数并在请求正常结束或异常抛出时确定性归零")
    void shouldTrackInFlightRequestsAndCleanupOnException() throws ServletException, IOException {
        assertEquals(0L, traceFilter.getActiveRequestCount());

        AtomicReference<Long> countDuringRequest = new AtomicReference<>(-1L);
        traceFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            countDuringRequest.set(traceFilter.getActiveRequestCount());
        });

        assertEquals(1L, countDuringRequest.get());
        assertEquals(0L, traceFilter.getActiveRequestCount());

        // 业务过滤器链抛出异常时，finally 块仍需严格归零并清理 MDC
        MockHttpServletRequest failReq = new MockHttpServletRequest();
        failReq.addHeader("X-Trace-Id", "err-trace-001");
        assertThrows(RuntimeException.class, () ->
                traceFilter.doFilter(failReq, new MockHttpServletResponse(), (req, res) -> {
                    assertEquals(1L, traceFilter.getActiveRequestCount());
                    throw new RuntimeException("boom");
                }));

        assertEquals(0L, traceFilter.getActiveRequestCount());
        assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
    }

    @Test
    @DisplayName("入站 X-Trace-Id 校验：放行合法字符集，拒绝 CRLF/空格/超长值并重生成安全 UUID")
    void shouldSanitizeInvalidInboundTraceId() throws ServletException, IOException {
        // 1. 允许合法字符 [a-zA-Z0-9\-_.:] 且长度 <= 64 原样透传
        String validTrace = "svc-A_01.node:99";
        MockHttpServletRequest validReq = new MockHttpServletRequest("GET", "/api/orders");
        validReq.addHeader("X-Trace-Id", validTrace);
        MockHttpServletResponse validResp = new MockHttpServletResponse();
        AtomicReference<String> validMdc = new AtomicReference<>();
        traceFilter.doFilter(validReq, validResp, (req, res) -> validMdc.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY)));
        assertEquals(validTrace, validMdc.get());
        assertEquals(validTrace, validResp.getHeader("X-Trace-Id"));

        // 2. 含 CRLF 换行注入、空格、特殊字符或超出 maxLength(64) 时必须丢弃并生成 32 位安全 UUID
        String[] maliciousValues = {
                "evil\r\nFakeLog: admin",
                "evil\nline",
                "trace with space",
                "trace$script<1>",
                "a".repeat(65)
        };
        for (String bad : maliciousValues) {
            MockHttpServletRequest badReq = new MockHttpServletRequest("GET", "/api/orders");
            badReq.addHeader("X-Trace-Id", bad);
            MockHttpServletResponse badResp = new MockHttpServletResponse();
            AtomicReference<String> generatedMdc = new AtomicReference<>();

            traceFilter.doFilter(badReq, badResp, (req, res) -> generatedMdc.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY)));

            String safeId = generatedMdc.get();
            assertNotNull(safeId);
            assertNotEquals(bad, safeId);
            assertEquals(32, safeId.length());
            assertTrue(safeId.matches("^[a-zA-Z0-9]+$"));
            assertEquals(safeId, badResp.getHeader("X-Trace-Id"));
            assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
        }

        // 3. 验证精确边界 length == 1、length == maxLength (64) 放行，空字符串 "" 回退 UUID，maxLength <= 0 回退默认值 64
        for (String boundaryValid : new String[]{"a", "x".repeat(64)}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
            req.addHeader("X-Trace-Id", boundaryValid);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            traceFilter.doFilter(req, resp, (r, s) -> {});
            assertEquals(boundaryValid, resp.getHeader("X-Trace-Id"));
        }

        MockHttpServletRequest emptyReq = new MockHttpServletRequest("GET", "/api/orders");
        emptyReq.addHeader("X-Trace-Id", "");
        MockHttpServletResponse emptyResp = new MockHttpServletResponse();
        traceFilter.doFilter(emptyReq, emptyResp, (r, s) -> {});
        assertEquals(32, emptyResp.getHeader("X-Trace-Id").length());

        properties.getTrace().setMaxLength(16);
        TraceFilter shortLimitFilter = new TraceFilter(properties);
        MockHttpServletRequest longReq = new MockHttpServletRequest("GET", "/api/orders");
        longReq.addHeader("X-Trace-Id", "12345678901234567");
        MockHttpServletResponse longResp = new MockHttpServletResponse();
        shortLimitFilter.doFilter(longReq, longResp, (req, res) -> {});
        assertNotEquals("12345678901234567", longResp.getHeader("X-Trace-Id"));
        assertEquals(32, longResp.getHeader("X-Trace-Id").length());

        properties.getTrace().setMaxLength(0);
        TraceFilter nonPositiveLimitFilter = new TraceFilter(properties);
        String valid64 = "b".repeat(64);
        MockHttpServletRequest fallbackLimitReq = new MockHttpServletRequest("GET", "/api/orders");
        fallbackLimitReq.addHeader("X-Trace-Id", valid64);
        MockHttpServletResponse fallbackLimitResp = new MockHttpServletResponse();
        nonPositiveLimitFilter.doFilter(fallbackLimitReq, fallbackLimitResp, (r, s) -> {});
        assertEquals(valid64, fallbackLimitResp.getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("优雅停机控制面端点不计入在途业务请求 activeRequests，但仍保留 traceId 与 MDC 清理")
    void shouldExcludeShutdownControlPlaneRequestsFromActiveCount() throws ServletException, IOException {
        for (String controlPath : new String[]{
                "/pre-stop",
                "/pre-stop/",
                "/pre-stop;jsessionid=123",
                "/actuator/pre-stop",
                "//actuator//pre-stop;v=1/"
        }) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", controlPath);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            AtomicReference<Long> activeInside = new AtomicReference<>(-1L);
            AtomicReference<String> mdcInside = new AtomicReference<>();

            traceFilter.doFilter(req, resp, (r, s) -> {
                activeInside.set(traceFilter.getActiveRequestCount());
                mdcInside.set(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
            });

            assertEquals(0L, activeInside.get(), "Control plane URI should not increment activeRequests: " + controlPath);
            assertNotNull(mdcInside.get());
            assertEquals(mdcInside.get(), resp.getHeader("X-Trace-Id"));
            assertNull(MDC.get(TraceFilter.MDC_TRACE_ID_KEY));
        }

        // contextPath 为 /pre 时不得误剥离 /pre-stop 前缀；且未完整命中段边界的业务路径仍正常计数
        MockHttpServletRequest partialPrefixReq = new MockHttpServletRequest("GET", "/pre/api/orders");
        partialPrefixReq.setContextPath("/pre");
        AtomicReference<Long> bizActive = new AtomicReference<>(-1L);
        traceFilter.doFilter(partialPrefixReq, new MockHttpServletResponse(),
                (r, s) -> bizActive.set(traceFilter.getActiveRequestCount()));
        assertEquals(1L, bizActive.get());

        // 支持自定义 mvcEndpointPath 及带矩阵参数的 contextPath 探测路径
        properties.getShutdown().setMvcEndpointPath("/internal/drain");
        TraceFilter customFilter = new TraceFilter(properties);
        MockHttpServletRequest customReq = new MockHttpServletRequest("POST", "/svc/internal;v=1/drain/");
        customReq.setContextPath("/svc");
        AtomicReference<Long> customActive = new AtomicReference<>(-1L);
        customFilter.doFilter(customReq, new MockHttpServletResponse(),
                (r, s) -> customActive.set(customFilter.getActiveRequestCount()));
        assertEquals(0L, customActive.get());

        // 关闭 graceful shutdown 时，控制面路径恢复按普通请求计数
        properties.getShutdown().setEnabled(false);
        TraceFilter disabledShutdownFilter = new TraceFilter(properties);
        MockHttpServletRequest reqWhenDisabled = new MockHttpServletRequest("GET", "/pre-stop");
        AtomicReference<Long> disabledActive = new AtomicReference<>(-1L);
        disabledShutdownFilter.doFilter(reqWhenDisabled, new MockHttpServletResponse(),
                (r, s) -> disabledActive.set(disabledShutdownFilter.getActiveRequestCount()));
        assertEquals(1L, disabledActive.get());
    }
}
