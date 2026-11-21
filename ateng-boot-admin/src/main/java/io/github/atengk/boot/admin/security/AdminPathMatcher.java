package io.github.atengk.boot.admin.security;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot Admin 路径归一化与精确端点匹配工具
 * <p>
 * 负责剥离 Servlet contextPath、折叠连续斜杠并清除矩阵参数（{@code ;...}），
 * 将生命周期端点精确锚定到 SBA {@code context-path}，防止宿主业务同名路径被误劫持或子路径越权绕过。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class AdminPathMatcher {

    private static final String INSTANCES_SEGMENT = "/instances";

    private AdminPathMatcher() {
    }

    /**
     * 规范化上下文路径（去除前后空白与尾斜杠，保证以单个 {@code /} 开头；根路径或空值返回 {@code ""}）
     *
     * @param contextPath 原始上下文路径
     * @return 规范化后的路径前缀（如 {@code "/admin"} 或 {@code ""}）
     */
    public static String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath)) {
            return "";
        }
        String normalized = normalizeSegments(contextPath.trim());
        return "/".equals(normalized) ? "" : normalized;
    }

    /**
     * 对请求路径执行剥离 Servlet contextPath、折叠连续斜杠及剔除矩阵参数归一化
     *
     * @param rawPath            原始请求 URI 或路径
     * @param servletContextPath Servlet 容器 contextPath（可为空）
     * @return 归一化后的应用内相对路径（始终以 {@code /} 开头）
     */
    public static String normalizeRequestPath(String rawPath, String servletContextPath) {
        if (!StringUtils.hasText(rawPath)) {
            return "";
        }
        String pathWithoutQuery = rawPath.trim();
        int queryIdx = pathWithoutQuery.indexOf('?');
        if (queryIdx >= 0) {
            pathWithoutQuery = pathWithoutQuery.substring(0, queryIdx);
        }
        String normalized = normalizeSegments(pathWithoutQuery);
        String normalizedServletCtx = normalizeContextPath(servletContextPath);
        if (!normalizedServletCtx.isEmpty()) {
            if (normalized.equals(normalizedServletCtx)) {
                return "/";
            }
            if (normalized.startsWith(normalizedServletCtx + "/")) {
                normalized = normalized.substring(normalizedServletCtx.length());
            }
        }
        return normalized;
    }

    /**
     * 判断请求是否精确命中 SBA 实例注册集合端点 {@code {adminContextPath}/instances}
     *
     * @param rawPath            原始请求 URI 或路径
     * @param servletContextPath Servlet contextPath
     * @param adminContextPath   SBA Server contextPath
     * @return 精确命中时返回 {@code true}
     */
    public static boolean isInstancesCollectionEndpoint(String rawPath, String servletContextPath, String adminContextPath) {
        String normalizedPath = normalizeRequestPath(rawPath, servletContextPath);
        if (normalizedPath.isEmpty()) {
            return false;
        }
        String target = normalizeContextPath(adminContextPath) + INSTANCES_SEGMENT;
        return target.equals(normalizedPath);
    }

    /**
     * 判断请求是否严格命中 SBA 单实例详情/注销端点 {@code {adminContextPath}/instances/{id}}
     * <p>
     * 仅允许单段实例 ID 后缀，严格拒绝对 {@code .../instances/{id}/actuator/**} 等多段子路径放行。
     *
     * @param rawPath            原始请求 URI 或路径
     * @param servletContextPath Servlet contextPath
     * @param adminContextPath   SBA Server contextPath
     * @return 精确命中单段实例路径时返回 {@code true}
     */
    public static boolean isSingleInstanceEndpoint(String rawPath, String servletContextPath, String adminContextPath) {
        String normalizedPath = normalizeRequestPath(rawPath, servletContextPath);
        if (normalizedPath.isEmpty()) {
            return false;
        }
        String prefix = normalizeContextPath(adminContextPath) + INSTANCES_SEGMENT + "/";
        if (!normalizedPath.startsWith(prefix)) {
            return false;
        }
        String remaining = normalizedPath.substring(prefix.length());
        return !remaining.isEmpty()
                && remaining.indexOf('/') < 0
                && !".".equals(remaining)
                && !"..".equals(remaining);
    }

    private static String normalizeSegments(String rawPath) {
        String[] parts = rawPath.split("/");
        List<String> cleanSegments = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int matrixIdx = part.indexOf(';');
            String clean = matrixIdx >= 0 ? part.substring(0, matrixIdx) : part;
            if (!clean.isEmpty()) {
                cleanSegments.add(clean);
            }
        }
        if (cleanSegments.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", cleanSegments);
    }
}
