package io.github.atengk.boot.observability.security;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 纯 JDK 17 原生 CIDR 子网与 IP 地址匹配器
 * <p>
 * 依托底层位运算与 {@link InetAddress} 实现零外部依赖的高性能 IP 白名单判定：
 * 1. 兼容标准 IPv4 精确匹配（如 {@code 127.0.0.1}）与 CIDR 掩码段（如 {@code 10.0.0.0/8}、{@code 192.168.1.0/24}、{@code 0.0.0.0/0}）；
 * 2. 兼容 IPv6 精确匹配（如 {@code ::1}）与 CIDR 掩码段（如 {@code fe80::/10}、{@code 2001:db8::/32}、{@code ::/0}）；
 * 3. 严格执行前置数值语法校验，阻断非数值字符串，坚决杜绝触发网络 DNS 解析造成请求挂起；
 * 4. 自动剥离 IPv6 作用域标识（Scope ID，如 {@code fe80::1%eth0}），并兼容本地环回主机（{@code 127.0.0.1} 与 {@code ::1}）跨协议等价映射。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public final class CidrMatcher {

    private final List<String> patterns;

    /**
     * 基于规则列表构造匹配器实例
     *
     * @param patterns IP 或 CIDR 网段规则集合
     */
    public CidrMatcher(Collection<String> patterns) {
        this.patterns = (patterns != null) ? List.copyOf(patterns) : Collections.emptyList();
    }

    /**
     * 判定指定客户端 IP 是否命中当前匹配器的任意规则
     *
     * @param clientIp 待判定的客户端 IP
     * @return true 表示命中
     */
    public boolean matches(String clientIp) {
        return matchesAny(clientIp, this.patterns);
    }

    /**
     * 判定指定客户端 IP 是否匹配单个 IP 或 CIDR 规则
     *
     * @param clientIp 待判定的客户端 IP 地址（如 {@code 192.168.1.100}、{@code 10.0.0.5}）
     * @param cidrOrIp 单个 IP 或 CIDR 规则（如 {@code 192.168.1.0/24}、{@code 127.0.0.1}、{@code ::1}）
     * @return true 表示属于该网段或等于该 IP；若任一参数为空或格式非法则返回 false
     */
    public static boolean matches(String clientIp, String cidrOrIp) {
        if (clientIp == null || clientIp.isBlank() || cidrOrIp == null || cidrOrIp.isBlank()) {
            return false;
        }

        String targetIp = clientIp.trim();
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

        // 剥离 IPv6 作用域标识（例如 fe80::1%eth0 或 0:0:0:0:0:0:0:1%0）
        int targetScope = targetIp.indexOf('%');
        if (targetScope >= 0) {
            targetIp = targetIp.substring(0, targetScope);
        }
        int baseScope = baseIp.indexOf('%');
        if (baseScope >= 0) {
            baseIp = baseIp.substring(0, baseScope);
        }

        // 严格数值检查：防范 DNS 解析挂起
        if (!isNumericIp(targetIp) || !isNumericIp(baseIp)) {
            return false;
        }

        try {
            InetAddress targetAddr = InetAddress.getByName(targetIp);
            InetAddress baseAddr = InetAddress.getByName(baseIp);

            byte[] targetBytes = targetAddr.getAddress();
            byte[] baseBytes = baseAddr.getAddress();

            if (targetBytes.length != baseBytes.length) {
                // 兼容本地环回地址跨协议映射（127.0.0.1 与 ::1）
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
     * 判定指定客户端 IP 是否命中规则集合中的任意一项
     *
     * @param clientIp   待判定的客户端 IP 地址
     * @param cidrsOrIps IP 或 CIDR 网段规则集合
     * @return true 表示命中至少一条规则；集合为空或无匹配返回 false
     */
    public static boolean matchesAny(String clientIp, Collection<String> cidrsOrIps) {
        if (clientIp == null || clientIp.isBlank() || cidrsOrIps == null || cidrsOrIps.isEmpty()) {
            return false;
        }
        for (String rule : cidrsOrIps) {
            if (matches(clientIp, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定指定客户端 IP 是否命中规则数组中的任意一项
     *
     * @param clientIp   待判定的客户端 IP 地址
     * @param cidrsOrIps IP 或 CIDR 网段规则数组
     * @return true 表示命中至少一条规则；数组为空或无匹配返回 false
     */
    public static boolean matchesAny(String clientIp, String... cidrsOrIps) {
        if (clientIp == null || clientIp.isBlank() || cidrsOrIps == null || cidrsOrIps.length == 0) {
            return false;
        }
        return matchesAny(clientIp, Arrays.asList(cidrsOrIps));
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
