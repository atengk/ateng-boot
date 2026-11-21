package io.github.atengk.boot.admin.admission;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.properties.DnsResolutionMode;
import io.github.atengk.boot.admin.security.AdminSecretMatcher;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CidrMatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Spring Boot Admin 默认客户端实例注册准入策略实现
 * 封装客户端集群共享密钥校验、四层 SSRF 纵深防御（云元数据/CGNAT/ULA/组播硬阻断、私网开关、CIDR 白黑名单与 DNS 防重绑定）及正则白名单过滤
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
     * 内部服务发现注册来源标识（仅限服务端内部组件传参，不适用于外部 HTTP 请求）
     */
    public static final String DISCOVERY_SOURCE = "discovery";

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Pattern IPV4_DECIMAL_SEGMENT = Pattern.compile("^[0-9]+$");
    private static final Pattern IPV4_OCTAL_SEGMENT = Pattern.compile("^0[0-7]+$");
    private static final Pattern IPV4_HEX_SEGMENT = Pattern.compile("^0[xX][0-9a-fA-F]+$");
    private static final Pattern IPV6_HEXTET_PATTERN = Pattern.compile("^[0-9a-fA-F]{1,4}$");

    private final AtengAdminServerProperties properties;
    private final HostAddressResolver hostAddressResolver;
    private final List<Pattern> compiledAllowedUrlPatterns;

    public DefaultAdminInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        this(properties, HostAddressResolver.SYSTEM);
    }

    /**
     * 基于配置属性与可注入 DNS 解析接缝构造准入策略（构造期 Fail-Fast 预编译正则规则）
     *
     * @param properties          Admin Server 配置属性
     * @param hostAddressResolver 主机名 DNS 解析器（不可为 null）
     */
    public DefaultAdminInstanceAdmissionPolicy(AtengAdminServerProperties properties,
                                               HostAddressResolver hostAddressResolver) {
        this.properties = properties;
        this.hostAddressResolver = Objects.requireNonNull(hostAddressResolver, "hostAddressResolver must not be null");
        this.compiledAllowedUrlPatterns = compileAllowedUrlPatterns(properties);
    }

    private static List<Pattern> compileAllowedUrlPatterns(AtengAdminServerProperties properties) {
        if (properties == null || properties.getAllowedUrlPatterns() == null || properties.getAllowedUrlPatterns().isEmpty()) {
            return Collections.emptyList();
        }
        List<Pattern> compiled = new ArrayList<>(properties.getAllowedUrlPatterns().size());
        for (String regex : properties.getAllowedUrlPatterns()) {
            if (!StringUtils.hasText(regex)) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException ex) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST,
                        "非法的 allowed-url-patterns 正则配置: [" + regex + "]", ex);
            }
        }
        return Collections.unmodifiableList(compiled);
    }

    @Override
    public AdminAdmissionResult evaluate(InstanceRegistration registration, HttpServletRequest request) {
        // 1. 仅内部无 HTTP 请求上下文且 source=discovery 的服务发现实例豁免共享密钥；外部 HTTP 请求严禁凭 source 绕过
        boolean isInternalDiscovery = request == null
                && registration != null
                && DISCOVERY_SOURCE.equalsIgnoreCase(registration.getSource());

        if (!isInternalDiscovery && properties != null && StringUtils.hasText(properties.getClientSecret())) {
            String clientSecret = request != null ? request.getHeader(CLIENT_SECRET_HEADER) : null;
            if (!isSecretMatch(clientSecret, properties.getClientSecret())) {
                log.warn("[Admission Policy] 注册拦截：客户端未提供有效的 [{}] 标头", CLIENT_SECRET_HEADER);
                return AdminAdmissionResult.reject(HttpStatus.FORBIDDEN, "Invalid " + CLIENT_SECRET_HEADER);
            }
        }

        // 2. 拒绝空注册载荷（registration == null），防止空包穿透准入核验
        if (registration == null) {
            log.warn("[Admission Policy] 注册拦截：实例注册载荷为空");
            return AdminAdmissionResult.reject(HttpStatus.BAD_REQUEST, "Empty instance registration payload");
        }

        // 3. 验证注册实例 URL 安全性（四层 SSRF 纵深防线）
        String serviceUrl = registration.getServiceUrl();
        String managementUrl = registration.getManagementUrl();
        String healthUrl = registration.getHealthUrl();

        if (!isUrlSafe(serviceUrl) || !isUrlSafe(managementUrl) || !isUrlSafe(healthUrl)) {
            log.warn("[Admission Policy] 注册拦截：上报 URL 命中安全黑名单或非法格式！serviceUrl=[{}], managementUrl=[{}], healthUrl=[{}]",
                    serviceUrl, managementUrl, healthUrl);
            return AdminAdmissionResult.reject(HttpStatus.FORBIDDEN, "URL forbidden by SSRF Guard Policy");
        }

        return AdminAdmissionResult.admit();
    }

    @Override
    public boolean isOutboundUrlAllowed(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        return isUrlSafe(url);
    }

    /**
     * 校验上报及出站 URL 是否安全合法，全面阻断凭据混淆、云元数据、高危保留段、CIDR 违规与 DNS 重绑定攻击
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
            if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }

            if (!isHostSafe(host)) {
                return false;
            }

            // 预编译正则白名单过滤（若配置了 allowed-url-patterns）
            if (!compiledAllowedUrlPatterns.isEmpty()) {
                boolean matched = compiledAllowedUrlPatterns.stream()
                        .anyMatch(pattern -> pattern.matcher(trimmedUrl).matches());
                if (!matched) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("[Admission Policy] URL 校验解析异常，判定为不安全: [{}]", urlStr, e);
            return false;
        }
    }

    private boolean isHostSafe(String host) throws UnknownHostException {
        String rawHost = stripBrackets(host.trim());
        // 1. 优先识别 IP 字面量（IPv6 或各进制/紧凑 IPv4 编码）
        if (rawHost.contains(":")) {
            int zoneIndex = rawHost.indexOf('%');
            String ipv6Literal = zoneIndex >= 0 ? rawHost.substring(0, zoneIndex) : rawHost;
            byte[] ipv6Bytes = parseIpv6Bytes(ipv6Literal);
            if (ipv6Bytes == null) {
                return false;
            }
            InetAddress address = canonicalizeAddress(InetAddress.getByAddress(ipv6Bytes));
            return isAddressAllowed(address);
        }

        String cleanHost = stripTrailingDots(rawHost).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(cleanHost)) {
            return false;
        }
        byte[] ipv4Bytes = parseIpv4LiteralBytes(cleanHost);
        if (ipv4Bytes != null) {
            return isAddressAllowed(InetAddress.getByAddress(ipv4Bytes));
        }

        // 2. 主机名为 localhost / *.localhost 时统一经由四层 IP 策略判定（含 allowLocalInstances 与 CIDR 白黑名单）
        if ("localhost".equals(cleanHost) || cleanHost.endsWith(".localhost")) {
            return isAddressAllowed(InetAddress.getByAddress(cleanHost, new byte[]{127, 0, 0, 1}));
        }

        // 3. 非字面量域名：依据 DnsResolutionMode 执行 DNS 解析防重绑定/泛域名解析（如 nip.io）
        DnsResolutionMode mode = (properties != null && properties.getDnsResolutionMode() != null)
                ? properties.getDnsResolutionMode()
                : DnsResolutionMode.FAIL_OPEN;
        if (mode == DnsResolutionMode.DISABLED) {
            return true;
        }
        try {
            InetAddress[] resolved = hostAddressResolver.resolve(cleanHost);
            if (resolved == null || resolved.length == 0) {
                return mode != DnsResolutionMode.FAIL_CLOSED;
            }
            for (InetAddress address : resolved) {
                if (address == null || !isAddressAllowed(canonicalizeAddress(address))) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException ex) {
            if (mode == DnsResolutionMode.FAIL_CLOSED) {
                log.warn("[Admission Policy] FAIL_CLOSED 模式拦截无法解析的主机名: [{}]", cleanHost);
                return false;
            }
            return true;
        }
    }

    private InetAddress canonicalizeAddress(InetAddress address) throws UnknownHostException {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return address;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16 && hasEmbeddedIpv4Bytes(raw)) {
            return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16));
        }
        return address;
    }

    private boolean isAddressAllowed(InetAddress address) {
        if (address == null) {
            return false;
        }
        // 第一层：不可旁路硬阻断（云元数据/Link-Local、CGNAT 100.64.0.0/10、IPv6 ULA fc00::/7、组播地址）
        if (isHardBlockedAddress(address)) {
            return false;
        }

        // 第二层：环回与通配绑定地址（127.0.0.0/8、::1、0.0.0.0、::）受 allowLocalInstances 控制
        boolean allowLocal = properties == null || properties.isAllowLocalInstances();
        if ((address.isLoopbackAddress() || address.isAnyLocalAddress()) && !allowLocal) {
            return false;
        }

        String canonicalIp = extractCanonicalIp(address);
        // 第三层：显式 CIDR 黑名单（优先于白名单）
        List<String> blockedCidrs = properties != null ? properties.getBlockedCidrs() : Collections.emptyList();
        if (blockedCidrs != null && !blockedCidrs.isEmpty() && CidrMatcher.matchesAny(canonicalIp, blockedCidrs)) {
            return false;
        }

        // 第四层：显式 CIDR 白名单（若配置，则允许作为私网例外并严格收敛至白名单范围）
        List<String> allowedCidrs = properties != null ? properties.getAllowedCidrs() : Collections.emptyList();
        if (allowedCidrs != null && !allowedCidrs.isEmpty()) {
            return CidrMatcher.matchesAny(canonicalIp, allowedCidrs);
        }

        // 无显式 CIDR 白名单时，校验 RFC 1918 私网阻断开关
        if (properties != null && properties.isBlockPrivateNetworks() && address.isSiteLocalAddress()) {
            return false;
        }
        return true;
    }

    private boolean isHardBlockedAddress(InetAddress address) {
        if (address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 169.254.0.0/16 链路本地与云元数据
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 100.64.0.0/10 CGNAT 共享地址空间（涵盖阿里云元数据 100.100.100.200）
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }
        if (bytes.length == 16) {
            // fc00::/7 IPv6 唯一本地地址 ULA（涵盖 AWS IPv6 IMDS fd00:ec2::254）
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    private String extractCanonicalIp(InetAddress address) {
        String ip = address.getHostAddress();
        int zoneIdx = ip.indexOf('%');
        return zoneIdx >= 0 ? ip.substring(0, zoneIdx) : ip;
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
     *
     * @param actual   实际携带密钥
     * @param expected 期望配置密钥
     * @return 完全一致时返回 {@code true}
     */
    protected boolean isSecretMatch(String actual, String expected) {
        return AdminSecretMatcher.constantTimeEquals(actual, expected);
    }
}
