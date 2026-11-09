package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.trace.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通用请求体可重读过滤器与包装类单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("通用请求体可重读过滤器与包装类单元测试")
class ContentCachingFilterTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("CachedBodyHttpServletRequest 支持重复多次读取输入流、Reader 与缓存字节")
    void shouldSupportRepeatedStreamReading() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/order");
        String content = "{\"id\":1001,\"name\":\"ateng-order\"}";
        request.setContent(content.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        // 第一次读取流
        byte[] firstRead = cachedRequest.getInputStream().readAllBytes();
        assertThat(new String(firstRead, StandardCharsets.UTF_8)).isEqualTo(content);

        // 第二次重复读取流
        byte[] secondRead = cachedRequest.getInputStream().readAllBytes();
        assertThat(new String(secondRead, StandardCharsets.UTF_8)).isEqualTo(content);

        // 通过 Reader 读取
        String readerContent = cachedRequest.getReader().readLine();
        assertThat(readerContent).isEqualTo(content);

        // 读取原始字节数组副本
        assertThat(cachedRequest.getCachedBody()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(cachedRequest.getContentLength()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(cachedRequest.getContentLengthLong()).isEqualTo((long) content.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("CachedBodyHttpServletRequest 当 Content-Length 预先超限时快速失败拦截防 OOM (413)")
    void shouldThrowWhenContentLengthExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data");
        byte[] body = new byte[1024];
        request.setContent(body);
        request.addHeader("Content-Length", 1024);

        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(request, 512))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体大小超过允许的最大限制: 512 字节")
                .extracting("code")
                .isEqualTo(CommonResultCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("CachedBodyHttpServletRequest 当流式读取实际数据超限时快速失败拦截防 OOM (413)")
    void shouldThrowWhenActualStreamExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data");
        byte[] body = new byte[2048];
        request.setContent(body);
        // 不声明 Content-Length，模拟分块传输

        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(request, DataSize.ofBytes(1024)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体大小超过允许的最大限制: 1024 字节")
                .extracting("code")
                .isEqualTo(CommonResultCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("ContentCachingFilter 优先级紧随 TraceFilter 之后")
    void shouldHaveCorrectOrder() {
        ContentCachingFilter filter = new ContentCachingFilter();
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 30);
        assertThat(filter.getOrder()).isEqualTo(ContentCachingFilter.ORDER);
    }

    @Test
    @DisplayName("ContentCachingFilter 对 POST/PUT/PATCH 普通 JSON 请求自动包装为 CachedBodyHttpServletRequest")
    void shouldWrapModifyingJsonRequests() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter(DataSize.ofMegabytes(2));

        for (String method : new String[]{"POST", "PUT", "PATCH"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/data");
            request.setContentType("application/json;charset=UTF-8");
            request.setContent("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicReference<ServletRequest> captured = new AtomicReference<>();
            FilterChain chain = (req, res) -> captured.set(req);

            filter.doFilter(request, response, chain);

            assertThat(captured.get())
                    .as("Method %s should be wrapped", method)
                    .isInstanceOf(CachedBodyHttpServletRequest.class);
        }
    }

    @Test
    @DisplayName("ContentCachingFilter 幂等防卫：已包装请求绝不重复包装")
    void shouldNotDoubleWrapAlreadyCachedRequest() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter();
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/api/data");
        rawRequest.setContentType("application/json");
        rawRequest.setContent("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));
        CachedBodyHttpServletRequest alreadyWrapped = new CachedBodyHttpServletRequest(rawRequest);

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(req);

        filter.doFilter(alreadyWrapped, response, chain);

        assertThat(captured.get()).isSameAs(alreadyWrapped);
    }

    @Test
    @DisplayName("ContentCachingFilter 自动放行 application/x-www-form-urlencoded 表单请求防破坏容器解析")
    void shouldSkipFormUrlEncodedRequest() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");
        request.setContentType("application/x-www-form-urlencoded");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(req);

        filter.doFilter(request, response, chain);

        assertThat(captured.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(captured.get()).isSameAs(request);
    }

    @Test
    @DisplayName("ContentCachingFilter 自动放行 multipart/form-data 文件上传请求防 OOM")
    void shouldSkipMultipartRequest() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/upload");
        request.setContentType("multipart/form-data; boundary=----WebKitFormBoundary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(req);

        filter.doFilter(request, response, chain);

        assertThat(captured.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(captured.get()).isSameAs(request);
    }

    @Test
    @DisplayName("ContentCachingFilter 自动放行 GET/DELETE 等只读请求")
    void shouldSkipReadOnlyMethods() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter();

        for (String method : new String[]{"GET", "DELETE", "HEAD", "OPTIONS"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/resource");
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicReference<ServletRequest> captured = new AtomicReference<>();
            FilterChain chain = (req, res) -> captured.set(req);

            filter.doFilter(request, response, chain);

            assertThat(captured.get())
                    .as("Method %s should not be wrapped", method)
                    .isNotInstanceOf(CachedBodyHttpServletRequest.class);
            assertThat(captured.get()).isSameAs(request);
        }
    }

    @Test
    @DisplayName("ContentCachingFilter 请求体超限异常兜底屏障：写出 200 + Result 413 JSON 与 X-Trace-Id 响应头，阻断容器 500")
    void shouldCatchPayloadTooLargeAndWriteJsonFallback() throws ServletException, IOException {
        ContentCachingFilter filter = new ContentCachingFilter(512);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data");
        request.setContentType("application/json;charset=UTF-8");
        request.addHeader("Content-Length", 1024);
        request.setContent(new byte[1024]);

        MockHttpServletResponse response = new MockHttpServletResponse();
        String testTraceId = "test-trace-caching-413";
        MDC.put(TraceFilter.MDC_TRACE_ID_KEY, testTraceId);

        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        FilterChain chain = (req, res) -> captured.set(req);

        filter.doFilter(request, response, chain);

        // 验证后续 Filter 链被阻断
        assertThat(captured.get()).isNull();

        // 验证写出 HTTP 200 与 Result.fail(413)
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(TraceFilter.DEFAULT_HEADER)).isEqualTo(testTraceId);
        assertThat(response.getContentType()).contains("application/json");

        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("\"code\":413");
        assertThat(content).contains("请求体大小超过允许的最大限制: 512 字节");
        assertThat(content).contains("\"traceId\":\"" + testTraceId + "\"");
    }
}
