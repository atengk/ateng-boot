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
}
