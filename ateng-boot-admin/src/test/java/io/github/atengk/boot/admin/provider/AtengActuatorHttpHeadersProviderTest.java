package io.github.atengk.boot.admin.provider;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengActuatorHttpHeadersProvider 单元测试
 * 覆盖四阶优先级多模态出站认证（Basic Auth、Bearer Auth、实例 Token 与兜底 Token）
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AtengActuatorHttpHeadersProvider 单元测试")
class AtengActuatorHttpHeadersProviderTest {

    @Test
    @DisplayName("第一优先级：当实例 Metadata 包含 user.name 与 user.password 时挂载标准 Authorization: Basic 请求头")
    void testBasicAuthCredentials() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback-token");
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("order-service", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_USER_NAME, "admin")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_USER_PASSWORD, "secret123")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-basic")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString("admin:secret123".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expectedBasic);
        assertThat(headers.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();
    }

    @Test
    @DisplayName("第一优先级扩展：支持识别 spring.security.user.name 与 spring.security.user.password 别名")
    void testSpringSecurityBasicAuthCredentials() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("pay-service", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_SPRING_SECURITY_USER_NAME, "sec-user")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_SPRING_SECURITY_USER_PASSWORD, "sec-pass")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-spring-sec")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString("sec-user:sec-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expectedBasic);
    }

    @Test
    @DisplayName("第二优先级：当实例 Metadata 包含 bearer-token 时挂载标准 Authorization: Bearer 请求头")
    void testBearerToken() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback-token");
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("user-service", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_BEARER_TOKEN, "jwt-sample-token-xyz")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_ACTUATOR_TOKEN, "ignored-token")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-bearer")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer jwt-sample-token-xyz");
        assertThat(headers.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();
    }

    @Test
    @DisplayName("第二优先级扩展：当实例 Metadata 包含 jwt-token 时亦能正确挂载 Authorization: Bearer 请求头")
    void testJwtToken() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("inventory-service", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_JWT_TOKEN, "jwt-token-777")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-jwt")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer jwt-token-777");
    }

    @Test
    @DisplayName("第三优先级：当实例 Metadata 中仅包含 actuator-token 时，挂载至 X-Actuator-Token 请求头")
    void testExtractTokenFromInstanceMetadata() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback-token");

        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("order-service", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_ACTUATOR_TOKEN, "instance-specific-token")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-1")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER))
                .isEqualTo("instance-specific-token");
        assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    @DisplayName("第四优先级：当实例 Metadata 未配置任何凭据时，保底使用服务端 default-actuator-token")
    void testFallbackToDefaultActuatorToken() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("global-fallback-token-888");

        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("pay-service", "http://localhost:8080/actuator/health")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-2")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER))
                .isEqualTo("global-fallback-token-888");
        assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    @DisplayName("优先级抢占：同时配置 Basic、Bearer 与 Token 时，Basic Auth 优先胜出")
    void testPriorityLadderBasicOverBearerAndToken() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback-token");
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("multi-auth-svc", "http://localhost:8080/actuator/health")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_USER_NAME, "admin")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_USER_PASSWORD, "123456")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_BEARER_TOKEN, "my-bearer-token")
                .metadata(AtengActuatorHttpHeadersProvider.METADATA_ACTUATOR_TOKEN, "my-actuator-token")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-precedence")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString("admin:123456".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo(expectedBasic);
        assertThat(headers.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();
    }

    @Test
    @DisplayName("当实例与服务端均未配置 token 时，返回空请求头且不包含 X-Actuator-Token")
    void testNoTokenConfigured() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("");

        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("gateway-service", "http://localhost:8080/actuator/health")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-3")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.containsKey(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isFalse();
        assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    @DisplayName("防御性测试：当 instance 为 null 或 registration 为 null 时，安全返回空 HttpHeaders 或保底 Token")
    void testDefensiveNullSafety() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback");
        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        assertThat(provider.getHeaders(null)).isNotNull();
        assertThat(provider.getHeaders(null).isEmpty()).isTrue();

        Instance bareInstance = Instance.create(InstanceId.of("inst-bare"));
        HttpHeaders headers = provider.getHeaders(bareInstance);
        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER)).isEqualTo("fallback");
    }
}
