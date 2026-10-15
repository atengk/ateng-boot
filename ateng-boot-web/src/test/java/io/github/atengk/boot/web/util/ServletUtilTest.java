package io.github.atengk.boot.web.util;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet 上下文门面工具单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("Servlet 上下文门面工具单测")
class ServletUtilTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("通过 RequestContextHolder 获取当前请求与响应对象")
    void shouldGetRequestAndResponseFromContext() {
        assertThat(ServletUtil.getRequest()).isNull();
        assertThat(ServletUtil.getResponse()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        assertThat(ServletUtil.getRequest()).isSameAs(request);
        assertThat(ServletUtil.getResponse()).isSameAs(response);
    }

    @Test
    @DisplayName("提取指定请求头参数值")
    void shouldGetHeaderValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Custom-Header", "CustomValue123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(ServletUtil.getHeader("X-Custom-Header")).isEqualTo("CustomValue123");
        assertThat(ServletUtil.getHeader(request, "X-Custom-Header")).isEqualTo("CustomValue123");
        assertThat(ServletUtil.getHeader(request, "Non-Existent")).isNull();
        assertThat(ServletUtil.getHeader(null, "X-Custom-Header")).isNull();
    }

    @Test
    @DisplayName("提取指定 Cookie 及其取值")
    void shouldGetCookieAndCookieValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Cookie tokenCookie = new Cookie("AUTH_TOKEN", "token_value_999");
        request.setCookies(tokenCookie);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(ServletUtil.getCookieValue("AUTH_TOKEN")).isEqualTo("token_value_999");
        assertThat(ServletUtil.getCookieValue(request, "AUTH_TOKEN")).isEqualTo("token_value_999");
        assertThat(ServletUtil.getCookie(request, "AUTH_TOKEN")).isNotNull();
        assertThat(ServletUtil.getCookie(request, "NON_EXIST")).isNull();
        assertThat(ServletUtil.getCookieValue(request, "NON_EXIST")).isNull();
        assertThat(ServletUtil.getCookieValue(null, "AUTH_TOKEN")).isNull();
    }

    @Test
    @DisplayName("正确识别 JSON 请求与 AJAX 异步请求")
    void shouldIdentifyJsonAndAjaxRequest() {
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.setContentType(MediaType.APPLICATION_JSON_VALUE);
        assertThat(ServletUtil.isJsonRequest(req1)).isTrue();
        assertThat(ServletUtil.isAjaxRequest(req1)).isTrue();

        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        assertThat(ServletUtil.isJsonRequest(req2)).isTrue();
        assertThat(ServletUtil.isAjaxRequest(req2)).isTrue();

        MockHttpServletRequest req3 = new MockHttpServletRequest();
        req3.addHeader("X-Requested-With", "XMLHttpRequest");
        assertThat(ServletUtil.isAjaxRequest(req3)).isTrue();

        MockHttpServletRequest req4 = new MockHttpServletRequest();
        assertThat(ServletUtil.isJsonRequest(req4)).isFalse();
        assertThat(ServletUtil.isAjaxRequest(req4)).isFalse();

        assertThat(ServletUtil.isJsonRequest(null)).isFalse();
        assertThat(ServletUtil.isAjaxRequest(null)).isFalse();
    }

    @Test
    @DisplayName("安全直接输出 UTF-8 JSON 文本到 Response")
    void shouldWriteJsonToResponse() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String json = "{\"code\":200,\"msg\":\"ok\"}";

        ServletUtil.writeJson(response, json);

        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString()).isEqualTo(json);

        // 空安全防御
        ServletUtil.writeJson(null, json);
        ServletUtil.writeJson(response, null);
    }
}
