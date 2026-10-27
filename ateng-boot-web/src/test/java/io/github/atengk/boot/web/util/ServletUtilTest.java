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

    @Test
    @DisplayName("文件下载响应头：遵循 RFC 6266 双重文件名编码且中文字符防乱码")
    void shouldSetDownloadHeaderWithRfc6266Compliance() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String filename = "用户账单明细 2026.xlsx";

        ServletUtil.setDownloadHeader(response, filename);

        assertThat(response.getContentType()).contains(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");

        String disposition = response.getHeader(ServletUtil.HEADER_CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment;");
        assertThat(disposition).contains("filename*=UTF-8''");
        // 空格转为 %20 且包含 UTF-8 编码
        assertThat(disposition).contains("%202026.xlsx");

        // 自定义 ContentType 测试
        MockHttpServletResponse customResponse = new MockHttpServletResponse();
        ServletUtil.setDownloadHeader(customResponse, "report.pdf", MediaType.APPLICATION_PDF_VALUE);
        assertThat(customResponse.getContentType()).contains(MediaType.APPLICATION_PDF_VALUE);
        assertThat(customResponse.getHeader(ServletUtil.HEADER_CONTENT_DISPOSITION)).contains("report.pdf");
    }

    @Test
    @DisplayName("字节数组流式下载：正确写入 Content-Length、响应头与二进制数据")
    void shouldWriteDownloadByteArray() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] payload = "Hello Ateng Boot Stream Download".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ServletUtil.writeDownload(response, "test_file.txt", payload);

        assertThat(response.getContentAsByteArray()).isEqualTo(payload);
        assertThat(response.getHeader(ServletUtil.HEADER_CONTENT_DISPOSITION)).contains("test_file.txt");
        assertThat(response.getContentLength()).isEqualTo(payload.length);
    }

    @Test
    @DisplayName("输入流流式下载：基于 transferTo 传输数据并安全自动关闭")
    void shouldWriteDownloadInputStream() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] payload = "Stream Transfer Test Data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(payload);

        ServletUtil.writeDownload(response, "stream_test.bin", in);

        assertThat(response.getContentAsByteArray()).isEqualTo(payload);
        assertThat(response.getHeader(ServletUtil.HEADER_CONTENT_DISPOSITION)).contains("stream_test.bin");
    }

    @Test
    @DisplayName("下载辅助方法全方位空安全与边界防御")
    void shouldHandleDownloadDefensiveNulls() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        // setDownloadHeader 空防御
        ServletUtil.setDownloadHeader(null, "test.txt");
        ServletUtil.setDownloadHeader(response, null);
        ServletUtil.setDownloadHeader(response, "   ");
        assertThat(response.getHeader(ServletUtil.HEADER_CONTENT_DISPOSITION)).isNull();

        // writeDownload(byte[]) 空防御
        ServletUtil.writeDownload(null, "test.txt", new byte[0]);
        ServletUtil.writeDownload(response, null, new byte[0]);
        ServletUtil.writeDownload(response, "test.txt", (byte[]) null);

        // writeDownload(InputStream) 空防御
        ServletUtil.writeDownload(null, "test.txt", java.io.InputStream.nullInputStream());
        ServletUtil.writeDownload(response, null, java.io.InputStream.nullInputStream());
        ServletUtil.writeDownload(response, "test.txt", (java.io.InputStream) null);
    }

    @Test
    @DisplayName("matchesAnyPath 路径匹配与边界防御测试")
    void shouldMatchAnyPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/app");
        request.setRequestURI("/app/api/v1/users/1001");

        // 匹配 contextPath 剥离后的路径与原始 URI
        assertThat(ServletUtil.matchesAnyPath(request, java.util.List.of("/api/**"))).isTrue();
        assertThat(ServletUtil.matchesAnyPath(request, java.util.List.of("/app/api/**"))).isTrue();
        assertThat(ServletUtil.matchesAnyPath(request, java.util.List.of("/other/**"))).isFalse();

        // 空值与边界防御
        assertThat(ServletUtil.matchesAnyPath(null, java.util.List.of("/api/**"))).isFalse();
        assertThat(ServletUtil.matchesAnyPath(request, (java.util.Collection<String>) null)).isFalse();
        assertThat(ServletUtil.matchesAnyPath(request, java.util.Collections.emptyList())).isFalse();
    }
}

