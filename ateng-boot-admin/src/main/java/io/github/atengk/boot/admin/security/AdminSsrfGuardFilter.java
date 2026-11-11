package io.github.atengk.boot.admin.security;

import com.fasterxml.jackson.databind.JsonNode;
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
public class AdminSsrfGuardFilter extends OncePerRequestFilter {

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

    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy) {
        this(admissionPolicy, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy, DataSize maxPayloadSize) {
        this(admissionPolicy, resolveMaxPayloadBytes(maxPayloadSize));
    }

    public AdminSsrfGuardFilter(AtengAdminServerProperties properties) {
        this(new DefaultAdminInstanceAdmissionPolicy(properties),
                properties != null ? properties.getMaxRegistrationPayloadSize() : null);
    }

    private AdminSsrfGuardFilter(AdminInstanceAdmissionPolicy admissionPolicy, long maxPayloadBytes) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy must not be null");
        this.maxPayloadBytes = maxPayloadBytes > 0 ? maxPayloadBytes : DEFAULT_MAX_PAYLOAD_BYTES;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 仅拦截向 SBA 注册新实例的 POST 请求（如 /instances 或 {contextPath}/instances）
        if ("POST".equalsIgnoreCase(request.getMethod()) && isInstancesEndpoint(path)) {
            // 1. 使用带容量上限保护的可重读包装器包装，避免大报文 OOM 与单向流读取破坏
            ResettableContentCachingRequestWrapper wrappedRequest;
            try {
                wrappedRequest = new ResettableContentCachingRequestWrapper(request, maxPayloadBytes);
            } catch (PayloadTooLargeException ex) {
                log.warn("[SSRF Guard] 注册拦截：请求体超出最大容量限制 [{} bytes]", maxPayloadBytes);
                reject(response, HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
                return;
            }
            byte[] body = wrappedRequest.getContentAsByteArray();

            // 2. 解析实例注册元数据
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

            // 3. 委托准入策略 SPI 执行合规与安全评估
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

            // 4. 传递已缓存 Body 且支持无损重读的包装请求向下游流转
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 判断请求路径是否匹配 SBA 注册根端点 (/instances 或 {contextPath}/instances)，规避形如 /api/user-instances 的边界误伤
     */
    private boolean isInstancesEndpoint(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return normalized.endsWith("/instances");
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
            long effectiveMaxBytes = maxPayloadBytes > 0 ? maxPayloadBytes : DEFAULT_MAX_PAYLOAD_BYTES;
            this.cachedBody = readBoundedBody(request, effectiveMaxBytes);
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
