package io.github.atengk.boot.web.util;

import io.github.atengk.boot.core.util.ValidatorUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 地址提取与识别工具
 * 支持多级反向代理（X-Forwarded-For 等标准头）解析，提取首个有效真实 IP，并安全兼容本地环回与 IPv6
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class IpUtil {

    public static final String LOCAL_IPV4 = "127.0.0.1";
    public static final String LOCAL_IPV6 = "0:0:0:0:0:0:0:1";
    public static final String LOCAL_IPV6_SHORT = "::1";
    public static final String UNKNOWN = "unknown";

    private static final String[] IP_HEADER_CANDIDATES = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private IpUtil() {
    }

    /**
     * 从当前请求上下文中获取客户端真实 IP 地址
     *
     * @return 客户端 IP 地址；若上下文无请求则返回空串
     */
    public static String getClientIp() {
        return getClientIp(ServletUtil.getRequest());
    }

    /**
     * 从指定 HttpServletRequest 提取客户端真实 IP 地址
     *
     * @param request HTTP 请求对象
     * @return 客户端 IP 地址；若请求为 null 则返回空串
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }

        String ip = null;
        for (String header : IP_HEADER_CANDIDATES) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.contains(",")) {
                String[] parts = value.split(",");
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (isValidIp(trimmed)) {
                        ip = trimmed;
                        break;
                    }
                }
            } else if (isValidIp(value.trim())) {
                ip = value.trim();
            }
            if (isValidIp(ip)) {
                break;
            }
        }

        if (!isValidIp(ip)) {
            ip = request.getRemoteAddr();
        }

        if (ip == null || ip.isBlank()) {
            return "";
        }

        ip = ip.trim();

        // IPv6 本地环回转换为标准 IPv4 环回
        if (LOCAL_IPV6.equals(ip) || LOCAL_IPV6_SHORT.equals(ip) || (LOCAL_IPV6 + "%0").equals(ip)) {
            return LOCAL_IPV4;
        }

        return ip;
    }

    /**
     * 校验 IP 字符串是否有效（非空、非 unknown 且符合严格的 IPv4/IPv6 或本地环回规范）
     *
     * @param ip 待校验 IP
     * @return true 表示有效
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || UNKNOWN.equalsIgnoreCase(ip.trim())) {
            return false;
        }
        String trimmed = ip.trim();
        if (LOCAL_IPV4.equals(trimmed) || LOCAL_IPV6.equals(trimmed) || LOCAL_IPV6_SHORT.equals(trimmed) || (LOCAL_IPV6 + "%0").equals(trimmed)) {
            return true;
        }
        return ValidatorUtil.isIp(trimmed);
    }
}
