package io.github.atengk.boot.web.xss;

import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XSS 过滤器单元测试
 * 覆盖请求包装行为、路径白名单排除与上下文标记
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("XssFilter 过滤器单元测试")
class XssFilterTest {

    @Test
    @DisplayName("1. 正常路径请求：自动包装为 XssHttpServletRequestWrapper 并执行参数清洗")
    void shouldWrapRequestForNormalPath() throws ServletException, IOException {
        WebProperties.XssProperties properties = new WebProperties.XssProperties();
        properties.setEnabled(true);
        properties.setMode(XssMode.ESCAPE);

        XssFilter filter = new XssFilter(properties, new XssCleaner(XssMode.ESCAPE));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/info");
        request.setParameter("nickname", "<script>alert('nick')</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean wasWrapped = new AtomicBoolean(false);
        AtomicReference<String> cleanedParam = new AtomicReference<>();

        FilterChain chain = (req, resp) -> {
            if (req instanceof XssHttpServletRequestWrapper) {
                wasWrapped.set(true);
                cleanedParam.set(req.getParameter("nickname"));
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(wasWrapped.get()).isTrue();
        assertThat(cleanedParam.get()).isEqualTo("&lt;script&gt;alert(&#39;nick&#39;)&lt;/script&gt;");
        assertThat(request.getAttribute(XssFilter.XSS_SKIP_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("2. 白名单路径请求：不执行包装，设置 XSS_SKIP_ATTRIBUTE 上下文标记")
    void shouldSkipAndMarkAttributeForExcludedPath() throws ServletException, IOException {
        WebProperties.XssProperties properties = new WebProperties.XssProperties();
        properties.setEnabled(true);
        properties.setExcludeUrls(List.of("/api/announcement/**", "/static/**"));

        XssFilter filter = new XssFilter(properties, new XssCleaner(XssMode.ESCAPE));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/announcement/publish");
        request.setParameter("content", "<h1>Announcement</h1>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean wasWrapped = new AtomicBoolean(false);

        FilterChain chain = (req, resp) -> {
            if (req instanceof XssHttpServletRequestWrapper) {
                wasWrapped.set(true);
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(wasWrapped.get()).isFalse();
        assertThat(request.getAttribute(XssFilter.XSS_SKIP_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("3. 请求头清洗：当 cleanHeaders=true 时清洗未排除标头，白名单排除标头不受影响（大小写不敏感）")
    void shouldCleanHeadersWhenEnabled() throws ServletException, IOException {
        WebProperties.XssProperties properties = new WebProperties.XssProperties();
        properties.setEnabled(true);
        properties.setCleanHeaders(true);
        properties.setMode(XssMode.ESCAPE);

        XssFilter filter = new XssFilter(properties, new XssCleaner(XssMode.ESCAPE));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Custom-Title", "<b>Dangerous Title</b><script>alert(1)</script>");
        request.addHeader("Authorization", "Bearer <secret-token-key>");
        request.addHeader("authorization", "Bearer <secret-token-key>");
        request.addHeader("Cookie", "sessionid=<cookie-token>");
        request.addHeader("X-Signature", "sig=<test-signature>");

        AtomicReference<HttpServletRequest> wrappedRef = new AtomicReference<>();
        FilterChain chain = (req, resp) -> {
            if (req instanceof HttpServletRequest httpReq) {
                wrappedRef.set(httpReq);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest wrapped = wrappedRef.get();
        assertThat(wrapped).isNotNull();

        // 未排除请求头被清洗
        assertThat(wrapped.getHeader("X-Custom-Title"))
                .isEqualTo("&lt;b&gt;Dangerous Title&lt;/b&gt;&lt;script&gt;alert(1)&lt;/script&gt;");

        // 默认白名单排除请求头原样放行（大小写不敏感）
        assertThat(wrapped.getHeader("Authorization")).isEqualTo("Bearer <secret-token-key>");
        assertThat(wrapped.getHeader("authorization")).isEqualTo("Bearer <secret-token-key>");
        assertThat(wrapped.getHeader("Cookie")).isEqualTo("sessionid=<cookie-token>");
        assertThat(wrapped.getHeader("X-Signature")).isEqualTo("sig=<test-signature>");
    }

    @Test
    @DisplayName("4. 请求头清洗：默认 cleanHeaders=false 时请求头保持原样不清洗")
    void shouldNotCleanHeadersWhenDisabled() throws ServletException, IOException {
        WebProperties.XssProperties properties = new WebProperties.XssProperties();
        properties.setEnabled(true);
        properties.setCleanHeaders(false);

        XssFilter filter = new XssFilter(properties, new XssCleaner(XssMode.ESCAPE));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.addHeader("X-Custom-Title", "<script>alert(1)</script>");

        AtomicReference<HttpServletRequest> wrappedRef = new AtomicReference<>();
        FilterChain chain = (req, resp) -> {
            if (req instanceof HttpServletRequest httpReq) {
                wrappedRef.set(httpReq);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest wrapped = wrappedRef.get();
        assertThat(wrapped.getHeader("X-Custom-Title")).isEqualTo("<script>alert(1)</script>");
    }
}
