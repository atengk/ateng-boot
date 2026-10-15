package io.github.atengk.boot.web.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    public static final String HEADER_X_REQUESTED_WITH = "X-Requested-With";
    public static final String XML_HTTP_REQUEST = "XMLHttpRequest";

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
}
