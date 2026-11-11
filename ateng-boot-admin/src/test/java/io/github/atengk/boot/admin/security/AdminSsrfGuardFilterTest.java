package io.github.atengk.boot.admin.security;

import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminSsrfGuardFilter 单元测试
 * 验证客户端握手密钥比对、169.254 云元数据硬阻断、本地环回控制与无损流包装重复读取
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AdminSsrfGuardFilter 防 SSRF 与注册清洗过滤器测试")
class AdminSsrfGuardFilterTest {

    private static final String VALID_SECRET = "CLUSTER_SHARED_SECRET_999";

    private AtengAdminServerProperties createProperties() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setClientSecret(VALID_SECRET);
        properties.setAllowLocalInstances(true);
        return properties;
    }

    @Test
    @DisplayName("合法请求携带有效密钥与合法URL时放行，且下游能无损重复读取请求体")
    void shouldAllowValidRegistrationAndSupportRepeatableRead() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        String jsonPayload = """
                {
                    "name": "user-service",
                    "serviceUrl": "http://user-service:8080/",
                    "managementUrl": "http://user-service:8081/actuator",
                    "healthUrl": "http://user-service:8081/actuator/health"
                }
                """;

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(jsonPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean downstreamExecuted = new AtomicBoolean(false);
        FilterChain filterChain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
                downstreamExecuted.set(true);
                // 验证下游第一次通过 getInputStream 读取流
                byte[] firstRead = req.getInputStream().readAllBytes();
                assertThat(new String(firstRead, StandardCharsets.UTF_8)).contains("user-service");

                // 验证下游第二次再次获取流并读取（无损流包装）
                byte[] secondRead = req.getInputStream().readAllBytes();
                assertThat(new String(secondRead, StandardCharsets.UTF_8)).contains("user-service");

                // 验证下游通过 getReader 读取
                String readerLine = req.getReader().readLine();
                assertThat(readerLine).isNotNull();
            }
        };

        filter.doFilter(request, response, filterChain);

        assertThat(downstreamExecuted.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("客户端未提供 X-Admin-Client-Secret 或密钥错误时直接 403 阻断")
    void shouldRejectInvalidSecret() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        // 1. 无请求头
        MockHttpServletRequest requestWithoutSecret = new MockHttpServletRequest("POST", "/instances");
        requestWithoutSecret.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse responseWithoutSecret = new MockHttpServletResponse();
        filter.doFilter(requestWithoutSecret, responseWithoutSecret, new MockFilterChain());
        assertThat(responseWithoutSecret.getStatus()).isEqualTo(403);
        assertThat(responseWithoutSecret.getContentAsString()).contains("Invalid X-Admin-Client-Secret");

        // 2. 错误密钥
        MockHttpServletRequest requestWithWrongSecret = new MockHttpServletRequest("POST", "/instances");
        requestWithWrongSecret.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, "WRONG_SECRET");
        requestWithWrongSecret.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse responseWithWrongSecret = new MockHttpServletResponse();
        filter.doFilter(requestWithWrongSecret, responseWithWrongSecret, new MockFilterChain());
        assertThat(responseWithWrongSecret.getStatus()).isEqualTo(403);
        assertThat(responseWithWrongSecret.getContentAsString()).contains("Invalid X-Admin-Client-Secret");
    }

    @Test
    @DisplayName("硬阻断 169.254.169.254 云厂商元数据地址")
    void shouldHardBlockCloudMetadataAddress() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        String maliciousPayload = """
                {
                    "name": "attacker-service",
                    "serviceUrl": "http://169.254.169.254/latest/meta-data/",
                    "managementUrl": "http://169.254.169.254/latest/meta-data/",
                    "healthUrl": "http://169.254.169.254/latest/meta-data/"
                }
                """;

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(maliciousPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("URL forbidden by SSRF Guard Policy");
    }

    @Test
    @DisplayName("当 allowLocalInstances=false 时，本地环回地址被拦截")
    void shouldBlockLoopbackWhenNotAllowed() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(false);
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        String localPayload = """
                {
                    "name": "local-service",
                    "serviceUrl": "http://127.0.0.1:8080/",
                    "managementUrl": "http://127.0.0.1:8080/actuator",
                    "healthUrl": "http://127.0.0.1:8080/actuator/health"
                }
                """;

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(localPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("URL forbidden by SSRF Guard Policy");
    }

    @Test
    @DisplayName("配置 URL 正则白名单时，非匹配地址被拦截阻断")
    void shouldEnforceAllowedUrlPatterns() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowedUrlPatterns(List.of("^http://.*\\.internal\\.corp(:\\d+)?(/.*)?$"));
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        // 1. 匹配白名单 -> 放行
        String validPayload = """
                {
                    "name": "corp-service",
                    "serviceUrl": "http://order.internal.corp:8080/",
                    "managementUrl": "http://order.internal.corp:8080/actuator",
                    "healthUrl": "http://order.internal.corp:8080/actuator/health"
                }
                """;
        MockHttpServletRequest validReq = new MockHttpServletRequest("POST", "/instances");
        validReq.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        validReq.setContent(validPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse validRes = new MockHttpServletResponse();
        filter.doFilter(validReq, validRes, new MockFilterChain());
        assertThat(validRes.getStatus()).isEqualTo(200);

        // 2. 不匹配白名单 -> 403
        String invalidPayload = """
                {
                    "name": "corp-service",
                    "serviceUrl": "http://evil.external.com:8080/",
                    "managementUrl": "http://evil.external.com:8080/actuator",
                    "healthUrl": "http://evil.external.com:8080/actuator/health"
                }
                """;
        MockHttpServletRequest invalidReq = new MockHttpServletRequest("POST", "/instances");
        invalidReq.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        invalidReq.setContent(invalidPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse invalidRes = new MockHttpServletResponse();
        filter.doFilter(invalidReq, invalidRes, new MockFilterChain());
        assertThat(invalidRes.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("非注册请求直接放行，不触发 SSRF 过滤与密钥拦截")
    void shouldBypassNonRegistrationRequests() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/instances");
        MockHttpServletResponse getResponse = new MockHttpServletResponse();
        AtomicBoolean getExecuted = new AtomicBoolean(false);
        filter.doFilter(getRequest, getResponse, (req, res) -> getExecuted.set(true));
        assertThat(getExecuted.get()).isTrue();

        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        AtomicBoolean loginExecuted = new AtomicBoolean(false);
        filter.doFilter(loginRequest, loginResponse, (req, res) -> loginExecuted.set(true));
        assertThat(loginExecuted.get()).isTrue();

        // 验证非 SBA /instances 根端点的同后缀业务 POST 接口不被误伤（如 /api/user-instances）
        MockHttpServletRequest businessPost = new MockHttpServletRequest("POST", "/api/user-instances");
        MockHttpServletResponse businessRes = new MockHttpServletResponse();
        AtomicBoolean businessExecuted = new AtomicBoolean(false);
        filter.doFilter(businessPost, businessRes, (req, res) -> businessExecuted.set(true));
        assertThat(businessExecuted.get()).isTrue();
        assertThat(businessRes.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("畸形非法 JSON 注册请求体返回 400 Bad Request")
    void shouldRejectMalformedJsonPayload() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        MockHttpServletRequest malformedReq = new MockHttpServletRequest("POST", "/instances");
        malformedReq.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        malformedReq.setContent("This is NOT a JSON payload!!".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse malformedRes = new MockHttpServletResponse();

        filter.doFilter(malformedReq, malformedRes, new MockFilterChain());

        assertThat(malformedRes.getStatus()).isEqualTo(400);
        assertThat(malformedRes.getContentAsString()).contains("Malformed JSON registration payload");
    }

    @Test
    @DisplayName("委托自定义准入策略并正确传递自定义拒绝状态码与描述")
    void shouldDelegateToCustomAdmissionPolicy() throws ServletException, IOException {
        AdminInstanceAdmissionPolicy customPolicy = (registration, request) -> {
            if (registration != null && "unapproved-vendor".equals(registration.getName())) {
                return AdminAdmissionResult.reject(HttpStatus.UNPROCESSABLE_ENTITY, "Vendor not in admission whitelist");
            }
            return AdminAdmissionResult.admit();
        };

        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(customPolicy);

        // 1. 触发自定义拒绝
        String payload = """
                {
                    "name": "unapproved-vendor",
                    "serviceUrl": "http://vendor:8080/",
                    "managementUrl": "http://vendor:8080/actuator",
                    "healthUrl": "http://vendor:8080/actuator/health"
                }
                """;
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/instances");
        req1.setContent(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, new MockFilterChain());
        assertThat(res1.getStatus()).isEqualTo(422);
        assertThat(res1.getContentAsString()).contains("Vendor not in admission whitelist");

        // 2. 触发自定义放行
        String validPayload = """
                {
                    "name": "approved-service",
                    "serviceUrl": "http://approved:8080/",
                    "managementUrl": "http://approved:8080/actuator",
                    "healthUrl": "http://approved:8080/actuator/health"
                }
                """;
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/instances");
        req2.setContent(validPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        AtomicBoolean chainExecuted = new AtomicBoolean(false);
        filter.doFilter(req2, res2, (req, res) -> chainExecuted.set(true));
        assertThat(chainExecuted.get()).isTrue();
    }

    @Test
    @DisplayName("注册请求体超过默认 1MB 容量上限时直接拦截并返回 413 Payload Too Large")
    void shouldRejectOversizedRegistrationPayloadWith413() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        int oversizedLength = 1024 * 1024 + 256;
        String largePadding = "a".repeat(oversizedLength);
        String oversizedPayload = "{\"name\":\"huge-svc\",\"serviceUrl\":\"http://order-svc:8080/\",\"metadata\":{\"pad\":\""
                + largePadding + "\"}}";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(oversizedPayload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean chainExecuted = new AtomicBoolean(false);
        filter.doFilter(request, response, (req, res) -> chainExecuted.set(true));

        assertThat(chainExecuted.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentAsString()).contains("exceeds maximum allowed size");
    }

    @Test
    @DisplayName("支持通过 DataSize 自定义较小注册载荷上限并在超限时阻断返回 413")
    void shouldEnforceCustomMaxPayloadSize() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        properties.setMaxRegistrationPayloadSize(DataSize.ofKilobytes(1));
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        String padding = "x".repeat(1500);
        String payload = "{\"name\":\"custom-limit-svc\",\"serviceUrl\":\"http://order-svc:8080/\",\"metadata\":{\"k\":\""
                + padding + "\"}}";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentAsString()).contains("1024 bytes");
    }

    @Test
    @DisplayName("未声明 Content-Length 的分块流在读取字节超过上限时仍触发 413 熔断")
    void shouldRejectChunkedOversizedStreamWithoutContentLengthHeader() throws ServletException, IOException {
        AtengAdminServerProperties properties = createProperties();
        properties.setMaxRegistrationPayloadSize(DataSize.ofKilobytes(1));
        AdminSsrfGuardFilter filter = new AdminSsrfGuardFilter(properties);

        String padding = "z".repeat(2048);
        String payload = "{\"name\":\"chunked-svc\",\"serviceUrl\":\"http://order-svc:8080/\",\"metadata\":{\"k\":\""
                + padding + "\"}}";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.addHeader(AdminSsrfGuardFilter.CLIENT_SECRET_HEADER, VALID_SECRET);
        request.setContent(payload.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }
}
