package io.github.atengk.boot.admin.admission;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "0.0.0.0", "::", "0:0:0:0:0:0:0:0"
    );

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
            URI uri = URI.create(urlStr.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = normalizeHost(host);

            // 1. 绝对硬阻断云厂商元数据地址 (169.254.0.0/16 网段)
            if (CLOUD_METADATA_HOST.equals(normalizedHost) || normalizedHost.startsWith(LINK_LOCAL_PREFIX)) {
                return false;
            }

            // 2. 本地环回地址弹性检查 (localhost / 127.0.0.1 / [::1] / 0.0.0.0)
            boolean isLoopback = LOOPBACK_HOSTS.contains(normalizedHost);
            if (isLoopback && (properties == null || !properties.isAllowLocalInstances())) {
                return false;
            }

            // 3. 正则白名单过滤 (若配置了 allowed-url-patterns 列表)
            if (properties != null) {
                List<String> allowedPatterns = properties.getAllowedUrlPatterns();
                if (allowedPatterns != null && !allowedPatterns.isEmpty()) {
                    boolean matched = allowedPatterns.stream().anyMatch(urlStr::matches);
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

    private String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]") && lower.length() > 2) {
            return lower.substring(1, lower.length() - 1);
        }
        return lower;
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
