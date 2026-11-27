package io.github.atengk.boot.web.xss;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XSS HTTP 请求包装器单元测试
 * 覆盖 getParameter、getParameterValues 与 getParameterMap 对 Query 及 Form 表单参数的无害化清洗
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("XssHttpServletRequestWrapper 请求包装器单元测试")
class XssHttpServletRequestWrapperTest {

    @Test
    @DisplayName("1. getParameter：单参数 HTML 转义清洗")
    void shouldCleanSingleParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("title", "<script>alert('xss')</script>");
        request.setParameter("normal", "plain text");

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request, new XssCleaner(XssMode.ESCAPE));

        assertThat(wrapper.getParameter("title")).isEqualTo("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
        assertThat(wrapper.getParameter("normal")).isEqualTo("plain text");
        assertThat(wrapper.getParameter("nonExistent")).isNull();
    }

    @Test
    @DisplayName("2. getParameterValues：多值数组每个元素统一清洗")
    void shouldCleanParameterValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("tags", new String[]{"<script>1</script>", "<b>safe</b>", null});

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request, new XssCleaner(XssMode.STRIP));

        String[] values = wrapper.getParameterValues("tags");
        assertThat(values).isNotNull();
        assertThat(values).containsExactly("", "safe", null);
        assertThat(wrapper.getParameterValues("missing")).isNull();
    }

    @Test
    @DisplayName("3. getParameterMap：返回所有参数清洗后的不可变映射快照")
    void shouldCleanParameterMap() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("name", "<b>admin</b>");
        request.setParameter("role", "<script>hack</script>");

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request, new XssCleaner(XssMode.ESCAPE));

        Map<String, String[]> map = wrapper.getParameterMap();
        assertThat(map).containsKeys("name", "role");
        assertThat(map.get("name")[0]).isEqualTo("&lt;b&gt;admin&lt;/b&gt;");
        assertThat(map.get("role")[0]).isEqualTo("&lt;script&gt;hack&lt;/script&gt;");
    }

    @Test
    @DisplayName("4. cleanHeaders=true：默认白名单放行传输层加解密头（大小写不敏感）且非白名单头正常转义")
    void shouldExcludeCryptoHeadersByDefaultWhenCleaningHeaders() {
        WebProperties.XssProperties xssProps = new WebProperties.XssProperties();
        xssProps.setCleanHeaders(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Encrypt-Key", "Ab+/Cd==<probe>");
        request.addHeader("X-Encrypt-Iv", "Iv+/1234==&");
        request.addHeader("x-key-id", "v1<tag>");
        request.addHeader("X-Encrypt-Alg", "RSA_AES_GCM\"quote'");
        request.addHeader("X-Signature", "sig+=/\"123");
        request.addHeader("X-Custom-Title", "<script>alert('hdr')</script>");

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request, new XssCleaner(XssMode.ESCAPE), xssProps);

        assertThat(wrapper.getHeader("X-Encrypt-Key")).isEqualTo("Ab+/Cd==<probe>");
        assertThat(wrapper.getHeader("x-encrypt-key")).isEqualTo("Ab+/Cd==<probe>");
        assertThat(wrapper.getHeader("X-Encrypt-Iv")).isEqualTo("Iv+/1234==&");
        assertThat(wrapper.getHeader("X-Key-Id")).isEqualTo("v1<tag>");
        assertThat(wrapper.getHeader("X-Encrypt-Alg")).isEqualTo("RSA_AES_GCM\"quote'");
        assertThat(wrapper.getHeader("X-Signature")).isEqualTo("sig+=/\"123");

        List<String> keyHeaders = Collections.list(wrapper.getHeaders("X-Encrypt-Key"));
        assertThat(keyHeaders).containsExactly("Ab+/Cd==<probe>");

        assertThat(wrapper.getHeader("X-Custom-Title")).isEqualTo("&lt;script&gt;alert(&#39;hdr&#39;)&lt;/script&gt;");
        List<String> customHeaders = Collections.list(wrapper.getHeaders("X-Custom-Title"));
        assertThat(customHeaders).containsExactly("&lt;script&gt;alert(&#39;hdr&#39;)&lt;/script&gt;");
    }
}
