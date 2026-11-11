package io.github.atengk.boot.admin.admission;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spring Boot Admin 默认客户端实例注册准入策略实现
 * 封装客户端集群共享密钥校验、云元数据（169.254.169.254）硬阻断、本地环回控制与正则白名单过滤
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class DefaultAdminInstanceAdmissionPolicy implements AdminInstanceAdmissionPolicy {

    /**
     * 客户端注册共享通信密钥请求头键名
     */
    public static final String CLIENT_SECRET_HEADER = "X-Admin-Client-Secret";

    /**
     * 云厂商元数据敏感 IP 地址（硬阻断）
     */
    private static final String CLOUD_METADATA_HOST = "169.254.169.254";

    /**
     * 链路本地地址前缀
     */
    private static final String LINK_LOCAL_PREFIX = "169.254.";

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Pattern IPV4_DECIMAL_SEGMENT = Pattern.compile("^[0-9]+$");
    private static final Pattern IPV4_OCTAL_SEGMENT = Pattern.compile("^0[0-7]+$");
    private static final Pattern IPV4_HEX_SEGMENT = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    private static final Pattern IPV6_HEXTET_PATTERN = Pattern.compile("^[0-9a-fA-F]{1,4}$");

    private enum HostSafetyCategory {
        LINK_LOCAL_OR_INVALID,
        LOOPBACK_OR_ANY_LOCAL,
        SAFE
    }

    private final AtengAdminServerProperties properties;

    public DefaultAdminInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public AdminAdmissionResult evaluate(InstanceRegistration registration, HttpServletRequest request) {
        // 1. 验证集群共享客户端通信密钥
        if (properties != null && StringUtils.hasText(properties.getClientSecret())) {
            String clientSecret = request != null ? request.getHeader(CLIENT_SECRET_HEADER) : null;
            if (!isSecretMatch(clientSecret, properties.getClientSecret())) {
                log.warn("[Admission Policy] 注册拦截：客户端未提供有效的 [{}] 标头", CLIENT_SECRET_HEADER);
                return AdminAdmissionResult.reject(HttpStatus.FORBIDDEN, "Invalid " + CLIENT_SECRET_HEADER);
            }
        }

        // 2. 验证注册实例 URL 安全性
        if (registration != null) {
            String serviceUrl = registration.getServiceUrl();
            String managementUrl = registration.getManagementUrl();
            String healthUrl = registration.getHealthUrl();

            if (!isUrlSafe(serviceUrl) || !isUrlSafe(managementUrl) || !isUrlSafe(healthUrl)) {
                log.warn("[Admission Policy] 注册拦截：上报 URL 命中敏感黑名单或非法格式！serviceUrl=[{}], managementUrl=[{}], healthUrl=[{}]",
                        serviceUrl, managementUrl, healthUrl);
                return AdminAdmissionResult.reject(HttpStatus.FORBIDDEN, "URL forbidden by SSRF Guard Policy");
            }
        }

        return AdminAdmissionResult.admit();
    }

    /**
     * 校验 URL 是否安全合规
     *
     * @param urlStr 目标 URL 字符串
     * @return true 若安全合规，false 若命中黑名单或非法格式
     */
    protected boolean isUrlSafe(String urlStr) {
        if (!StringUtils.hasText(urlStr)) {
            return true;
        }
        try {
            String trimmedUrl = urlStr.trim();
            URI uri = URI.create(trimmedUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return false;
            }
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }

            HostSafetyCategory category = classifyHost(host);
            // 1. 绝对硬阻断云厂商元数据 / 链路本地地址 (169.254.0.0/16、fe80::/10 及等价编码变体)
            if (category == HostSafetyCategory.LINK_LOCAL_OR_INVALID) {
                return false;
            }
            // 2. 本地环回与通配绑定地址弹性检查 (localhost / 127.0.0.0/8 / [::1] / 0.0.0.0 / [::] 等)
            if (category == HostSafetyCategory.LOOPBACK_OR_ANY_LOCAL
                    && (properties == null || !properties.isAllowLocalInstances())) {
                return false;
            }

            // 3. 正则白名单过滤 (若配置了 allowed-url-patterns 列表，与 URI 解析保持一致使用 trim 后 URL)
            if (properties != null) {
                List<String> allowedPatterns = properties.getAllowedUrlPatterns();
                if (allowedPatterns != null && !allowedPatterns.isEmpty()) {
                    boolean matched = allowedPatterns.stream().anyMatch(trimmedUrl::matches);
                    if (!matched) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("[Admission Policy] URL 校验解析异常，判定为不安全: [{}]", urlStr, e);
            return false;
        }
    }

    private HostSafetyCategory classifyHost(String host) throws UnknownHostException {
        String rawHost = stripBrackets(host.trim());
        if (rawHost.contains(":")) {
            int zoneIndex = rawHost.indexOf('%');
            String ipv6Literal = zoneIndex >= 0 ? rawHost.substring(0, zoneIndex) : rawHost;
            byte[] ipv6Bytes = parseIpv6Bytes(ipv6Literal);
            if (ipv6Bytes == null) {
                return HostSafetyCategory.LINK_LOCAL_OR_INVALID;
            }
            return classifyIpv6Bytes(ipv6Bytes);
        }

        String cleanHost = stripTrailingDots(rawHost).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(cleanHost)) {
            return HostSafetyCategory.LINK_LOCAL_OR_INVALID;
        }
        if ("localhost".equals(cleanHost) || cleanHost.endsWith(".localhost")) {
            return HostSafetyCategory.LOOPBACK_OR_ANY_LOCAL;
        }
        byte[] ipv4Bytes = parseIpv4LiteralBytes(cleanHost);
        if (ipv4Bytes != null) {
            return classifyIpv4Bytes(ipv4Bytes);
        }
        return HostSafetyCategory.SAFE;
    }

    private HostSafetyCategory classifyIpv6Bytes(byte[] ipv6Bytes) throws UnknownHostException {
        InetAddress address = InetAddress.getByAddress(ipv6Bytes);
        if (address.isLinkLocalAddress()) {
            return HostSafetyCategory.LINK_LOCAL_OR_INVALID;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return HostSafetyCategory.LOOPBACK_OR_ANY_LOCAL;
        }
        // 处理 IPv4-mapped (::ffff:a.b.c.d) 与 IPv4-compatible (::a.b.c.d) 内嵌 IPv4 地址
        if (hasEmbeddedIpv4Bytes(ipv6Bytes)) {
            byte[] embeddedIpv4 = Arrays.copyOfRange(ipv6Bytes, 12, 16);
            return classifyIpv4Bytes(embeddedIpv4);
        }
        return HostSafetyCategory.SAFE;
    }

    private HostSafetyCategory classifyIpv4Bytes(byte[] ipv4Bytes) throws UnknownHostException {
        InetAddress address = InetAddress.getByAddress(ipv4Bytes);
        if (address.isLinkLocalAddress()) {
            return HostSafetyCategory.LINK_LOCAL_OR_INVALID;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return HostSafetyCategory.LOOPBACK_OR_ANY_LOCAL;
        }
        String hostAddress = address.getHostAddress();
        if (CLOUD_METADATA_HOST.equals(hostAddress) || hostAddress.startsWith(LINK_LOCAL_PREFIX)) {
            return HostSafetyCategory.LINK_LOCAL_OR_INVALID;
        }
        return HostSafetyCategory.SAFE;
    }

    private boolean hasEmbeddedIpv4Bytes(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        boolean ipv4Compatible = bytes[10] == 0 && bytes[11] == 0;
        boolean ipv4Mapped = bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
        return ipv4Compatible || ipv4Mapped;
    }

    private String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private String stripTrailingDots(String host) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    private byte[] parseIpv4LiteralBytes(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return null;
        }
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            long parsed = parseIpv4Segment(parts[i]);
            if (parsed < 0) {
                return null;
            }
            values[i] = parsed;
        }
        byte[] result = new byte[4];
        if (parts.length == 1) {
            long v = values[0];
            if (v > 0xFFFFFFFFL) {
                return null;
            }
            result[0] = (byte) ((v >>> 24) & 0xFF);
            result[1] = (byte) ((v >>> 16) & 0xFF);
            result[2] = (byte) ((v >>> 8) & 0xFF);
            result[3] = (byte) (v & 0xFF);
            return result;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            if (values[i] > 0xFFL) {
                return null;
            }
            result[i] = (byte) values[i];
        }
        long tail = values[parts.length - 1];
        long maxTail = (1L << (8 * (5 - parts.length))) - 1;
        if (tail > maxTail) {
            return null;
        }
        for (int i = parts.length - 1; i < 4; i++) {
            int shift = 8 * (3 - i);
            result[i] = (byte) ((tail >>> shift) & 0xFF);
        }
        return result;
    }

    private long parseIpv4Segment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return -1L;
        }
        try {
            if (IPV4_HEX_SEGMENT.matcher(segment).matches()) {
                return Long.parseLong(segment.substring(2), 16);
            }
            if (IPV4_OCTAL_SEGMENT.matcher(segment).matches()) {
                return Long.parseLong(segment.substring(1), 8);
            }
            if (IPV4_DECIMAL_SEGMENT.matcher(segment).matches()) {
                return Long.parseLong(segment, 10);
            }
            return -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private byte[] parseIpv6Bytes(String ip) {
        if (!StringUtils.hasText(ip)) {
            return null;
        }
        String normalized = ip;
        if (ip.contains(".")) {
            int lastColon = ip.lastIndexOf(':');
            if (lastColon < 0) {
                return null;
            }
            byte[] ipv4Suffix = parseStrictDottedIpv4(ip.substring(lastColon + 1));
            if (ipv4Suffix == null) {
                return null;
            }
            String syntheticHex = String.format(Locale.ROOT, "%x:%x",
                    ((ipv4Suffix[0] & 0xFF) << 8) | (ipv4Suffix[1] & 0xFF),
                    ((ipv4Suffix[2] & 0xFF) << 8) | (ipv4Suffix[3] & 0xFF));
            normalized = ip.substring(0, lastColon + 1) + syntheticHex;
        }
        return parsePureIpv6Bytes(normalized);
    }

    private byte[] parseStrictDottedIpv4(String ipv4) {
        String[] parts = ipv4.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!IPV4_DECIMAL_SEGMENT.matcher(parts[i]).matches()) {
                return null;
            }
            try {
                int val = Integer.parseInt(parts[i], 10);
                if (val < 0 || val > 255) {
                    return null;
                }
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private byte[] parsePureIpv6Bytes(String ip) {
        int doubleColonIdx = ip.indexOf("::");
        if (doubleColonIdx >= 0 && ip.indexOf("::", doubleColonIdx + 2) >= 0) {
            return null;
        }
        String[] leftParts;
        String[] rightParts;
        if (doubleColonIdx >= 0) {
            String left = ip.substring(0, doubleColonIdx);
            String right = ip.substring(doubleColonIdx + 2);
            leftParts = left.isEmpty() ? new String[0] : left.split(":", -1);
            rightParts = right.isEmpty() ? new String[0] : right.split(":", -1);
            if (leftParts.length + rightParts.length >= 8) {
                return null;
            }
        } else {
            leftParts = ip.split(":", -1);
            rightParts = new String[0];
            if (leftParts.length != 8) {
                return null;
            }
        }

        byte[] result = new byte[16];
        int offset = 0;
        for (String group : leftParts) {
            if (!writeHextet(group, result, offset)) {
                return null;
            }
            offset += 2;
        }
        if (doubleColonIdx >= 0) {
            int missingGroups = 8 - leftParts.length - rightParts.length;
            offset += missingGroups * 2;
        }
        for (String group : rightParts) {
            if (!writeHextet(group, result, offset)) {
                return null;
            }
            offset += 2;
        }
        return result;
    }

    private boolean writeHextet(String group, byte[] target, int offset) {
        if (!IPV6_HEXTET_PATTERN.matcher(group).matches()) {
            return false;
        }
        int value = Integer.parseInt(group, 16);
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
        return true;
    }

    /**
     * 恒定时间比对密钥，防范时序侧信道反推攻击
     */
    protected boolean isSecretMatch(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
