package io.github.atengk.boot.admin.security;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.InstanceRegistration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.core.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 拦截客户端注册请求，委托准入策略 SPI 进行安全合规核验与防 SSRF 穿透
 * 采用可复位无损流包装有界缓存 Body，支持下游重复消费并防御超大载荷内存溢出
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
@Order(AdminSsrfGuardFilter.DEFAULT_ORDER)
public class AdminSsrfGuardFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 默认 Servlet 过滤器优先级：置于 SaTokenContextFilter（-104）之后、SaServletFilter（-100）之前
     * 确保真实容器中调用 {@code StpUtil.isLogin()} 时上下文已就绪且仍在鉴权屏障前沿执行
     */
    public static final int DEFAULT_ORDER = -102;

    /**
     * 客户端注册共享通信密钥请求头键名
     */
    public static final String CLIENT_SECRET_HEADER = DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER;

    /**
     * 默认注册请求体最大允许字节数（1MB）
     */
    public static final long DEFAULT_MAX_PAYLOAD_BYTES = 1024L * 1024L;

    private final AdminInstanceAdmissionPolicy admissionPolicy;
    private final long maxPayloadBytes;
    private final String clientSecret;
    private final String adminContextPath;
    private final int order;

    @Override
    public int getOrder() {
        return this.order;
    }

    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy) {
        this(admissionPolicy, DEFAULT_MAX_PAYLOAD_BYTES, null, null, DEFAULT_ORDER);
    }

    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy, DataSize maxPayloadSize) {
        this(admissionPolicy, resolveMaxPayloadBytes(maxPayloadSize), null, null, DEFAULT_ORDER);
    }

    public AdminSsrfGuardFilter(AtengAdminServerProperties properties) {
        this(new DefaultAdminInstanceAdmissionPolicy(properties), properties, null, DEFAULT_ORDER);
    }

    /**
     * 基于自定义准入策略、配置属性与 AdminServerProperties 构造防线过滤器
     *
     * @param admissionPolicy       准入策略 SPI
     * @param properties            企业级 Admin Server 属性配置
     * @param adminServerProperties Spring Boot Admin 原生属性（用于锚定 context-path）
     */
    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy,
                                AtengAdminServerProperties properties,
                                AdminServerProperties adminServerProperties) {
        this(admissionPolicy, properties, adminServerProperties, DEFAULT_ORDER);
    }

    /**
     * 基于自定义准入策略、配置属性、AdminServerProperties 与自定义过滤器顺序构造防线过滤器
     *
     * @param admissionPolicy       准入策略 SPI
     * @param properties            企业级 Admin Server 属性配置
     * @param adminServerProperties Spring Boot Admin 原生属性
     * @param order                 Servlet 过滤器执行优先级
     */
    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy,
                                AtengAdminServerProperties properties,
                                AdminServerProperties adminServerProperties,
                                int order) {
        this(admissionPolicy,
                resolveMaxPayloadBytes(properties != null ? properties.getMaxRegistrationPayloadSize() : null),
                properties != null ? properties.getClientSecret() : null,
                adminServerProperties != null ? adminServerProperties.getContextPath() : null,
                order);
    }

    private AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy,
                                 long maxPayloadBytes,
                                 String clientSecret,
                                 String adminContextPath,
                                 int order) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy must not be null");
        this.maxPayloadBytes = normalizeMaxPayloadBytes(maxPayloadBytes);
        this.clientSecret = clientSecret;
        this.adminContextPath = AdminPathMatcher.normalizeContextPath(adminContextPath);
        this.order = order;
    }

    private static long resolveMaxPayloadBytes(DataSize maxPayloadSize) {
        if (maxPayloadSize == null) {
            return DEFAULT_MAX_PAYLOAD_BYTES;
        }
        long bytes = maxPayloadSize.toBytes();
        if (bytes <= 0) {
            log.warn("[SSRF Guard] 配置的 maxRegistrationPayloadSize=[{}] 非正，回退默认值 [{} bytes]",
                    maxPayloadSize, DEFAULT_MAX_PAYLOAD_BYTES);
            return DEFAULT_MAX_PAYLOAD_BYTES;
        }
        return bytes;
    }

    private static long normalizeMaxPayloadBytes(long maxPayloadBytes) {
        return maxPayloadBytes > 0 ? maxPayloadBytes : DEFAULT_MAX_PAYLOAD_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String servletContextPath = request.getContextPath();
        String method = request.getMethod();

        // 1. 拦截向 SBA 注册新实例的 POST 请求（精确锚定 {adminContextPath}/instances）
        if ("POST".equalsIgnoreCase(method)
                && AdminPathMatcher.isInstancesCollectionEndpoint(path, servletContextPath, adminContextPath)) {
            // 1.1 使用带容量上限保护的可重读包装器包装，避免大报文 OOM 与单向流读取破坏
            ResettableContentCachingRequestWrapper wrappedRequest;
            try {
                wrappedRequest = new ResettableContentCachingRequestWrapper(request, maxPayloadBytes);
            } catch (PayloadTooLargeException ex) {
                log.warn("[SSRF Guard] 注册拦截：请求体超出最大容量限制 [{} bytes]", maxPayloadBytes);
                reject(response, HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
                return;
            }
            byte[] body = wrappedRequest.getContentAsByteArray();

            // 1.2 解析实例注册元数据
            InstanceRegistration registration = null;
            if (body != null && body.length > 0) {
                try {
                    JsonNode root = JsonUtil.getObjectMapper().readTree(body);
                    registration = InstanceRegistration.parse(root);
                } catch (Exception e) {
                    log.error("[SSRF Guard] 解析注册 Body 失败: {}", e.getMessage());
                    reject(response, HttpStatus.BAD_REQUEST, "Malformed JSON registration payload");
                    return;
                }
            }

            // 1.3 委托准入策略 SPI 执行合规与安全评估
            AdminAdmissionResult result = admissionPolicy.evaluate(registration, wrappedRequest);
            if (result == null || result.isRejected()) {
                HttpStatus status = (result != null && result.getStatus() != null)
                        ? result.getStatus()
                        : HttpStatus.FORBIDDEN;
                String reason = (result != null && StringUtils.hasText(result.getReason()))
                        ? result.getReason()
                        : "Registration rejected by admission policy";
                log.warn("[SSRF Guard] 注册请求未通过准入策略审核: status=[{}], reason=[{}]", status.value(), reason);
                reject(response, status, reason);
                return;
            }

            // 1.4 传递已缓存 Body 且支持无损重读的包装请求向下游流转
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        // 2. 拦截单实例下线注销 DELETE {adminContextPath}/instances/{id}：要求携带合法共享密钥或已登录会话
        if ("DELETE".equalsIgnoreCase(method)
                && AdminPathMatcher.isSingleInstanceEndpoint(path, servletContextPath, adminContextPath)
                && StringUtils.hasText(clientSecret)) {
            String providedSecret = request.getHeader(CLIENT_SECRET_HEADER);
            if (!isSecretMatch(providedSecret) && !isSessionAuthenticated()) {
                log.warn("[SSRF Guard] 注销拦截：未携带有效 [{}] 且不存在已登录管理会话，uri=[{}]",
                        CLIENT_SECRET_HEADER, path);
                reject(response, HttpStatus.FORBIDDEN,
                        "Missing valid X-Admin-Client-Secret or authenticated session for instance deregistration");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSecretMatch(String providedSecret) {
        return AdminSecretMatcher.matchesConfiguredSecret(providedSecret, clientSecret);
    }

    private boolean isSessionAuthenticated() {
        try {
            return StpUtil.isLogin();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 输出 JSON 格式拒绝响应
     */
    private void reject(HttpServletResponse response, HttpStatus status, String msg) throws IOException {
        AdminSecurityResponseUtil.writeJsonResponse(response, status, msg);
    }

    /**
     * 可无损重复读取的 HttpServletRequestWrapper 实现
     * 包装原始请求流，在有界容量保护下实现对 Request Body 的安全预读与下游多次消费
     */
    public static class ResettableContentCachingRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public ResettableContentCachingRequestWrapper(HttpServletRequest request) throws IOException {
            this(request, DEFAULT_MAX_PAYLOAD_BYTES);
        }

        public ResettableContentCachingRequestWrapper(HttpServletRequest request, long maxPayloadBytes) throws IOException {
            super(request);
            this.cachedBody = readBoundedBody(request, normalizeMaxPayloadBytes(maxPayloadBytes));
        }

        private static byte[] readBoundedBody(HttpServletRequest request, long maxPayloadBytes) throws IOException {
            long declaredLength = request.getContentLengthLong();
            if (declaredLength > maxPayloadBytes) {
                throw new PayloadTooLargeException(maxPayloadBytes);
            }

            ServletInputStream input = request.getInputStream();
            ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long totalRead = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                totalRead += read;
                if (totalRead > maxPayloadBytes) {
                    throw new PayloadTooLargeException(maxPayloadBytes);
                }
                bufferStream.write(chunk, 0, read);
            }
            return bufferStream.toByteArray();
        }

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

    /**
     * 注册请求载荷超出容量上限异常
     */
    public static class PayloadTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        public PayloadTooLargeException(long maxPayloadBytes) {
            super("Registration payload exceeds maximum allowed size of " + maxPayloadBytes + " bytes");
        }
    }
}
