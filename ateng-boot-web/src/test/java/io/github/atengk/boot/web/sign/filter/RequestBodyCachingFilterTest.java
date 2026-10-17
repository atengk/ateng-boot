package io.github.atengk.boot.web.sign.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 请求体缓存与防爆过滤器单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("请求体缓存与防爆过滤器单测")
class RequestBodyCachingFilterTest {

    @Test
    @DisplayName("CachedBodyHttpServletRequest 支持重复读取输入流与 Reader")
    void shouldSupportRepeatedStreamReading() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/order");
        String content = "{\"id\":1001,\"name\":\"test-order\"}";
        request.setContent(content.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        // 第一次读取
        byte[] firstRead = cachedRequest.getInputStream().readAllBytes();
        assertThat(new String(firstRead, StandardCharsets.UTF_8)).isEqualTo(content);

        // 第二次读取（验证重复可读性）
        byte[] secondRead = cachedRequest.getInputStream().readAllBytes();
        assertThat(new String(secondRead, StandardCharsets.UTF_8)).isEqualTo(content);

        // Reader 读取
        String readerContent = cachedRequest.getReader().readLine();
        assertThat(readerContent).isEqualTo(content);

        // 字节副本读取
        assertThat(cachedRequest.getCachedBody()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CachedBodyHttpServletRequest 请求体超限时快速失败拦截并防范 OOM")
    void shouldThrowWhenBodyExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/big");
        byte[] largeBody = new byte[1024]; // 1024 bytes
        request.setContent(largeBody);

        // 限制最大 512 字节
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(request, 512))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求体大小超过允许的最大限制");
    }

    @Test
    @DisplayName("RequestBodyCachingFilter 对普通 JSON POST 请求自动包装升级为可重读请求")
    void shouldWrapJsonPostRequest() throws ServletException, IOException {
        RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data");
        request.setContentType("application/json");
        request.setContent("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> capturedRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedRequest.set(req);

        filter.doFilter(request, response, chain);

        assertThat(capturedRequest.get()).isInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    @DisplayName("RequestBodyCachingFilter 自动跳过 application/x-www-form-urlencoded 表单请求，防破坏参数解析")
    void shouldSkipFormUrlEncodedRequest() throws ServletException, IOException {
        RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");
        request.setContentType("application/x-www-form-urlencoded");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> capturedRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedRequest.set(req);

        filter.doFilter(request, response, chain);

        assertThat(capturedRequest.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(capturedRequest.get()).isSameAs(request);
    }

    @Test
    @DisplayName("RequestBodyCachingFilter 自动跳过 multipart/form-data 文件上传请求")
    void shouldSkipMultipartRequest() throws ServletException, IOException {
        RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/upload");
        request.setContentType("multipart/form-data; boundary=---boundary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> capturedRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedRequest.set(req);

        filter.doFilter(request, response, chain);

        assertThat(capturedRequest.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(capturedRequest.get()).isSameAs(request);
    }

    @Test
    @DisplayName("RequestBodyCachingFilter 自动跳过 GET 请求")
    void shouldSkipGetRequest() throws ServletException, IOException {
        RequestBodyCachingFilter filter = new RequestBodyCachingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<ServletRequest> capturedRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedRequest.set(req);

        filter.doFilter(request, response, chain);

        assertThat(capturedRequest.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(capturedRequest.get()).isSameAs(request);
    }
}
