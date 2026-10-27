package io.github.atengk.boot.web.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Servlet 上下文与请求响应静态操作工具门面
 * 基于 Spring {@link RequestContextHolder} 安全获取当前请求与响应，并提供轻量便捷操作
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class ServletUtil {

    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CONTENT_DISPOSITION = "Content-Disposition";
    public static final String HEADER_X_REQUESTED_WITH = "X-Requested-With";
    public static final String XML_HTTP_REQUEST = "XMLHttpRequest";

    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    private ServletUtil() {
    }

    /**
     * 获取当前线程绑定的 HttpServletRequest 对象
     *
     * @return 当前请求对象；若非 Web 请求上下文则返回 null
     */
    public static HttpServletRequest getRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    /**
     * 获取当前线程绑定的 HttpServletResponse 对象
     *
     * @return 当前响应对象；若非 Web 请求上下文则返回 null
     */
    public static HttpServletResponse getResponse() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getResponse();
        }
        return null;
    }

    /**
     * 获取指定请求头参数值（当前请求上下文）
     *
     * @param name 请求头名称
     * @return 请求头值；无对应值或无请求上下文时返回 null
     */
    public static String getHeader(String name) {
        return getHeader(getRequest(), name);
    }

    /**
     * 获取指定请求头参数值
     *
     * @param request HTTP 请求对象
     * @param name    请求头名称
     * @return 请求头值；无对应值或请求为 null 时返回 null
     */
    public static String getHeader(HttpServletRequest request, String name) {
        if (request == null || name == null) {
            return null;
        }
        return request.getHeader(name);
    }

    /**
     * 获取指定名称的 Cookie 值（当前请求上下文）
     *
     * @param name Cookie 名称
     * @return Cookie 值；若不存在或无请求上下文返回 null
     */
    public static String getCookieValue(String name) {
        return getCookieValue(getRequest(), name);
    }

    /**
     * 获取指定名称的 Cookie 值
     *
     * @param request HTTP 请求对象
     * @param name    Cookie 名称
     * @return Cookie 值；若不存在或请求为 null 返回 null
     */
    public static String getCookieValue(HttpServletRequest request, String name) {
        Cookie cookie = getCookie(request, name);
        return cookie != null ? cookie.getValue() : null;
    }

    /**
     * 获取指定名称的 Cookie 对象
     *
     * @param request HTTP 请求对象
     * @param name    Cookie 名称
     * @return Cookie 对象；若不存在或请求为 null 返回 null
     */
    public static Cookie getCookie(HttpServletRequest request, String name) {
        if (request == null || name == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equalsIgnoreCase(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    /**
     * 判断指定请求是否为 JSON 请求（依据 Content-Type 或 Accept 头包含 application/json）
     *
     * @param request HTTP 请求对象
     * @return true 表示为 JSON 请求
     */
    public static boolean isJsonRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE)) {
            return true;
        }
        String accept = request.getHeader(HEADER_ACCEPT);
        return accept != null && accept.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * 判断指定请求是否为 AJAX 异步请求（Header 中含 X-Requested-With: XMLHttpRequest 或为 JSON 请求）
     *
     * @param request HTTP 请求对象
     * @return true 表示为 AJAX 请求
     */
    public static boolean isAjaxRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String xRequestedWith = request.getHeader(HEADER_X_REQUESTED_WITH);
        if (XML_HTTP_REQUEST.equalsIgnoreCase(xRequestedWith)) {
            return true;
        }
        return isJsonRequest(request);
    }

    /**
     * 向响应直接写入 UTF-8 JSON 文本
     *
     * @param response HTTP 响应对象
     * @param json     JSON 字符串
     * @throws IOException 写入异常
     */
    public static void writeJson(HttpServletResponse response, String json) throws IOException {
        if (response == null || json == null) {
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
        response.getWriter().flush();
    }

    /**
     * 设置安全防乱码的文件下载响应头（默认 application/octet-stream）
     * 遵循 RFC 6266 与 RFC 5987 规范，生成双重文件名声明（ASCII 兼容与 UTF-8 编码），
     * 彻底解决 Chrome、Edge、Safari、Firefox 等主流浏览器的中文与特殊字符乱码问题
     *
     * @param response HTTP 响应对象
     * @param filename 待下载的文件名称（如 "用户数据导出_2026.xlsx"）
     */
    public static void setDownloadHeader(HttpServletResponse response, String filename) {
        setDownloadHeader(response, filename, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    /**
     * 设置指定媒体类型的安全防乱码文件下载响应头
     *
     * @param response    HTTP 响应对象
     * @param filename    待下载的文件名称
     * @param contentType 响应内容类型（为 null 时默认 application/octet-stream）
     */
    public static void setDownloadHeader(HttpServletResponse response, String filename, String contentType) {
        if (response == null || filename == null || filename.isBlank()) {
            return;
        }
        String mimeType = (contentType != null && !contentType.isBlank()) ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        response.setContentType(mimeType);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiFallback = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        String contentDisposition = "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedFilename;
        response.setHeader(HEADER_CONTENT_DISPOSITION, contentDisposition);
    }

    /**
     * 向响应直接写入文件二进制字节数组完成流式下载
     * 内部自动配置安全防乱码响应头并写入 Content-Length
     *
     * @param response HTTP 响应对象
     * @param filename 下载文件名
     * @param data     文件字节数组
     * @throws IOException 写入异常
     */
    public static void writeDownload(HttpServletResponse response, String filename, byte[] data) throws IOException {
        if (response == null || filename == null || data == null) {
            return;
        }
        setDownloadHeader(response, filename);
        response.setContentLength(data.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(data);
            out.flush();
        }
    }

    /**
     * 向响应直接输出文件输入流完成流式下载
     * 基于 JDK 17 原生 transferTo 实现高吞吐零拷贝式流传输，自动关闭输入流并刷新响应流
     *
     * @param response    HTTP 响应对象
     * @param filename    下载文件名
     * @param inputStream 文件输入流（方法执行后将自动关闭该流）
     * @throws IOException 传输异常
     */
    public static void writeDownload(HttpServletResponse response, String filename, InputStream inputStream) throws IOException {
        if (response == null || filename == null || inputStream == null) {
            return;
        }
        setDownloadHeader(response, filename);
        try (InputStream in = inputStream; OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    /**
     * 判断当前 HTTP 请求的 URI 或 contextPath 相对路径是否匹配给定的 AntPath 规则集合中的任一规则
     *
     * @param request  HTTP 请求对象
     * @param patterns AntPath 规则集合（如 /api/**）
     * @return true 表示至少匹配其中一条规则；若请求或规则集为空则返回 false
     */
    public static boolean matchesAnyPath(HttpServletRequest request, Collection<String> patterns) {
        if (request == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = (StringUtils.hasText(contextPath) && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        for (String pattern : patterns) {
            if (StringUtils.hasText(pattern)) {
                String trimmed = pattern.trim();
                if (PATH_MATCHER.match(trimmed, uri) || PATH_MATCHER.match(trimmed, path)) {
                    return true;
                }
            }
        }
        return false;
    }
}

