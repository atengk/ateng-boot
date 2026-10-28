package io.github.atengk.boot.web.xss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
}
