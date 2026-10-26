package io.github.atengk.boot.admin.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 拦截客户端注册请求，防止恶意伪造实例与 SSRF 内网穿透渗透
 * 采用 ContentCachingRequestWrapper 无损缓存 Body，支持下游重复消费
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
public class AdminSsrfGuardFilter extends OncePerRequestFilter {

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

    private final AtengAdminServerProperties properties;

    public AdminSsrfGuardFilter(AtengAdminServerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 仅拦截向 SBA 注册新实例的 POST 请求（如 /instances 或 {contextPath}/instances）
        if ("POST".equalsIgnoreCase(request.getMethod()) && (path.endsWith("/instances") || path.endsWith("/instances/"))) {
            // 1. 验证集群共享客户端通信密钥
            if (properties != null && StringUtils.hasText(properties.getClientSecret())) {
                String clientSecret = request.getHeader(CLIENT_SECRET_HEADER);
                if (!isSecretMatch(clientSecret, properties.getClientSecret())) {
                    log.warn("[SSRF Guard] 注册拦截：客户端未提供有效的 [{}] 标头", CLIENT_SECRET_HEADER);
                    reject(response, HttpStatus.FORBIDDEN, "Invalid X-Admin-Client-Secret");
                    return;
                }
            }

            // 2. 使用可重读包装器包装以避免 Body 被流读取破坏
            ResettableContentCachingRequestWrapper wrappedRequest = new ResettableContentCachingRequestWrapper(request);
            byte[] body = wrappedRequest.getContentAsByteArray();

            if (body != null && body.length > 0) {
                try {
                    JsonNode root = JsonUtil.getObjectMapper().readTree(body);
                    String serviceUrl = root.path("serviceUrl").asText(null);
                    String managementUrl = root.path("managementUrl").asText(null);
                    String healthUrl = root.path("healthUrl").asText(null);

                    if (!isUrlSafe(serviceUrl) || !isUrlSafe(managementUrl) || !isUrlSafe(healthUrl)) {
                        log.warn("[SSRF Guard] 注册拦截：上报 URL 命中敏感黑名单或非法格式！serviceUrl=[{}], managementUrl=[{}], healthUrl=[{}]",
                                serviceUrl, managementUrl, healthUrl);
                        reject(response, HttpStatus.FORBIDDEN, "URL forbidden by SSRF Guard Policy");
                        return;
                    }
                } catch (Exception e) {
                    log.error("[SSRF Guard] 解析注册 Body 失败: {}", e.getMessage());
                    reject(response, HttpStatus.BAD_REQUEST, "Malformed JSON registration payload");
                    return;
                }
            }

            // 传递已缓存 Body 且支持无损重读的包装请求向下游流转
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 校验 URL 是否安全合规
     *
     * @param urlStr 目标 URL 字符串
     * @return true 若安全合规，false 若命中黑名单或非法格式
     */
    private boolean isUrlSafe(String urlStr) {
        if (!StringUtils.hasText(urlStr)) {
            return true;
        }
        try {
            URI uri = URI.create(urlStr.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }

            // 1. 绝对硬阻断云厂商元数据地址 (169.254.0.0/16 网段)
            if (CLOUD_METADATA_HOST.equalsIgnoreCase(host) || host.startsWith(LINK_LOCAL_PREFIX)) {
                return false;
            }

            // 2. 本地环回地址弹性检查 (localhost / 127.0.0.1 / ::1 / 0.0.0.0)
            boolean isLoopback = "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "0.0.0.0".equals(host);
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
            return false;
        }
    }

    /**
     * 恒定时间比对密钥，防范时序侧信道反推攻击
     */
    private boolean isSecretMatch(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 输出 JSON 格式拒绝响应
     */
    private void reject(HttpServletResponse response, HttpStatus status, String msg) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\"}", status.value(), msg));
    }

    /**
     * 可无损重复读取的 ContentCachingRequestWrapper 实现
     * 包装原始请求流，实现对 Request Body 的安全预读与下游多次消费
     */
    public static class ResettableContentCachingRequestWrapper extends ContentCachingRequestWrapper {

        private final byte[] cachedBody;

        public ResettableContentCachingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        @Override
        public byte[] getContentAsByteArray() {
            return this.cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(this.cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return bais.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
