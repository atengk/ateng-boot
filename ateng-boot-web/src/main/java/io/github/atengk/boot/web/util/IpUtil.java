package io.github.atengk.boot.web.util;

import io.github.atengk.boot.core.util.ValidatorUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
     * 规范化 IP 字符串（去除前后空格并将 IPv6 本地环回转换为标准 IPv4 环回）
     *
     * @param ip 原始 IP
     * @return 规整后的 IP
     */
    public static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String trimmed = ip.trim();
        if (LOCAL_IPV6.equals(trimmed) || LOCAL_IPV6_SHORT.equals(trimmed) || (LOCAL_IPV6 + "%0").equals(trimmed)) {
            return LOCAL_IPV4;
        }
        return trimmed;
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
     * 从当前请求上下文中获取客户端真实 IP 地址（配合授信反向代理防伪造）
     *
     * @param trustedProxies 授信反向代理 IP / CIDR 网段集合
     * @return 客户端 IP 地址；若上下文无请求则返回空串
     */
    public static String getClientIp(Collection<String> trustedProxies) {
        return getClientIp(ServletUtil.getRequest(), trustedProxies);
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
                    String trimmed = normalizeIp(part);
                    if (isValidIp(trimmed)) {
                        ip = trimmed;
                        break;
                    }
                }
            } else {
                String candidate = normalizeIp(value);
                if (isValidIp(candidate)) {
                    ip = candidate;
                }
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

        return normalizeIp(ip);
    }

    /**
     * 从指定 HttpServletRequest 提取客户端真实 IP 地址（配合授信反向代理防伪造校验）
     * 当 trustedProxies 为空时，保持 100% 既有提取行为兼容；
     * 当 trustedProxies 非空时，校验直连 remoteAddr 是否为授信代理：若非授信代理直接返回 remoteAddr 防伪造，
     * 若为授信代理则从转发链路中逆向剔除授信代理跳点提取首个未授信的真实客户端 IP
     *
     * @param request        HTTP 请求对象
     * @param trustedProxies 授信反向代理 IP / CIDR 网段集合
     * @return 客户端真实 IP 地址；若请求为 null 则返回空串
     */
    public static String getClientIp(HttpServletRequest request, Collection<String> trustedProxies) {
        if (request == null) {
            return "";
        }
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return getClientIp(request);
        }

        String remoteAddr = request.getRemoteAddr();
        String normalizedRemote = normalizeIp(remoteAddr);

        // 如果直连客户端不是授信代理，则转发头（X-Forwarded-For 等）不可信，直接返回 remoteAddr 防伪造
        if (!isInRange(normalizedRemote, trustedProxies)) {
            return normalizedRemote != null ? normalizedRemote : "";
        }

        // 授信代理场景：遍历候选请求头，从转发链中逆向剔除授信代理跳点提取真实客户端 IP
        String clientIp = null;
        String fallbackTrustedIp = null;
        for (String header : IP_HEADER_CANDIDATES) {
            String value = request.getHeader(header);
            if (value == null || value.isBlank()) {
                continue;
            }
            String[] parts = value.split(",");
            List<String> ipList = new ArrayList<>();
            for (String part : parts) {
                String candidate = normalizeIp(part);
                if (isValidIp(candidate)) {
                    ipList.add(candidate);
                }
            }
            if (ipList.isEmpty()) {
                continue;
            }

            // 从右向左（距当前代理最近的上一跳开始）查找首个非授信代理的真实客户端 IP
            for (int i = ipList.size() - 1; i >= 0; i--) {
                String candidate = ipList.get(i);
                if (!isInRange(candidate, trustedProxies)) {
                    clientIp = candidate;
                    break;
                }
            }

            // 若找到非授信真实客户端 IP，立即结束候选头遍历
            if (clientIp != null) {
                break;
            }

            // 若当前请求头内所有 IP 均为授信代理，记录链路最左端作为全授信链路兜底候选
            if (fallbackTrustedIp == null) {
                fallbackTrustedIp = ipList.get(0);
            }
        }

        String ip = clientIp != null ? clientIp : (fallbackTrustedIp != null ? fallbackTrustedIp : normalizedRemote);
        return ip != null ? ip : "";
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

    /**
     * 判定指定 IP 地址是否属于指定的目标 IP 或 CIDR 网段
     * 支持纯 JDK 17 原生位运算解析，兼容 IPv4（如 192.168.1.0/24, 10.0.0.0/8）与 IPv6（如 ::1, 2001:db8::/32）
     * 内部具备严格纯数值校验与异常拦截机制，杜绝触发网络 DNS 解析
     *
     * @param ip        待判定的客户端真实 IP
     * @param cidrOrIp  单个目标 IP 地址或带子网掩码前缀的 CIDR 网段（如 "192.168.1.0/24", "127.0.0.1", "::1"）
     * @return true 表示属于该网段或等于该 IP；若任一参数为空或格式非法则返回 false
     */
    public static boolean isInRange(String ip, String cidrOrIp) {
        if (ip == null || ip.isBlank() || cidrOrIp == null || cidrOrIp.isBlank()) {
            return false;
        }
        String targetIp = ip.trim();
        String rule = cidrOrIp.trim();

        String baseIp;
        int prefixLength;

        int slashIndex = rule.indexOf('/');
        if (slashIndex >= 0) {
            baseIp = rule.substring(0, slashIndex).trim();
            String prefixStr = rule.substring(slashIndex + 1).trim();
            try {
                prefixLength = Integer.parseInt(prefixStr);
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            baseIp = rule;
            prefixLength = -1;
        }

        // 剥离 IPv6 作用域标识（如 fe80::1%eth0）
        int targetScope = targetIp.indexOf('%');
        if (targetScope >= 0) {
            targetIp = targetIp.substring(0, targetScope);
        }
        int baseScope = baseIp.indexOf('%');
        if (baseScope >= 0) {
            baseIp = baseIp.substring(0, baseScope);
        }

        if (!isNumericIp(targetIp) || !isNumericIp(baseIp)) {
            return false;
        }

        try {
            InetAddress targetAddr = InetAddress.getByName(targetIp);
            InetAddress baseAddr = InetAddress.getByName(baseIp);

            byte[] targetBytes = targetAddr.getAddress();
            byte[] baseBytes = baseAddr.getAddress();

            if (targetBytes.length != baseBytes.length) {
                // 兼容本地环回主机地址在 IPv4 与 IPv6 之间的等价映射（如 127.0.0.1 与 ::1）
                if (targetAddr.isLoopbackAddress() && baseAddr.isLoopbackAddress()
                        && (prefixLength == -1 || prefixLength == baseBytes.length * 8)) {
                    return true;
                }
                return false;
            }

            int maxPrefix = targetBytes.length * 8;
            if (prefixLength == -1) {
                prefixLength = maxPrefix;
            } else if (prefixLength < 0 || prefixLength > maxPrefix) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != baseBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((targetBytes[fullBytes] & mask) != (baseBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判定指定 IP 地址是否命中指定规则集合中的任意一项
     *
     * @param ip        待判定的客户端真实 IP
     * @param cidrOrIps 单个 IP 或 CIDR 网段规则集合
     * @return true 表示命中其中至少一条规则；若无匹配或参数为空则返回 false
     */
    public static boolean isInRange(String ip, Collection<String> cidrOrIps) {
        if (ip == null || ip.isBlank() || cidrOrIps == null || cidrOrIps.isEmpty()) {
            return false;
        }
        for (String rule : cidrOrIps) {
            if (isInRange(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定指定 IP 地址是否命中指定规则数组中的任意一项
     *
     * @param ip        待判定的客户端真实 IP
     * @param cidrOrIps 单个 IP 或 CIDR 网段规则数组
     * @return true 表示命中其中至少一条规则；若无匹配或参数为空则返回 false
     */
    public static boolean isInRange(String ip, String... cidrOrIps) {
        if (ip == null || ip.isBlank() || cidrOrIps == null || cidrOrIps.length == 0) {
            return false;
        }
        for (String rule : cidrOrIps) {
            if (isInRange(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumericIpv4(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int dotCount = 0;
        int currentNum = 0;
        boolean hasDigitInPart = false;
        int partDigits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (!hasDigitInPart || dotCount >= 3) {
                    return false;
                }
                dotCount++;
                currentNum = 0;
                hasDigitInPart = false;
                partDigits = 0;
            } else if (c >= '0' && c <= '9') {
                hasDigitInPart = true;
                partDigits++;
                if (partDigits > 3) {
                    return false;
                }
                currentNum = currentNum * 10 + (c - '0');
                if (currentNum > 255) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return dotCount == 3 && hasDigitInPart;
    }

    private static boolean isNumericIpv6(String s) {
        if (s == null || s.isEmpty() || !s.contains(":")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.' || c == '%') {
                // 合法 IPv6 字符集
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumericIp(String s) {
        return isNumericIpv4(s) || isNumericIpv6(s);
    }
}
