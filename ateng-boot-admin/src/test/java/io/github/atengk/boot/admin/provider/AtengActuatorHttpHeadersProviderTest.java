package io.github.atengk.boot.admin.provider;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengActuatorHttpHeadersProvider 单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("AtengActuatorHttpHeadersProvider 单元测试")
class AtengActuatorHttpHeadersProviderTest {

    @Test
    @DisplayName("当实例 Metadata 中包含 actuator-token 时，优先挂载至 X-Actuator-Token 请求头")
    void testExtractTokenFromInstanceMetadata() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setDefaultActuatorToken("fallback-token");

        AtengActuatorHttpHeadersProvider provider = new AtengActuatorHttpHeadersProvider(properties);

        Registration registration = Registration.create("order-service", "http://localhost:8080/actuator/health")
                .metadata("actuator-token", "instance-specific-token")
                .build();
        Instance instance = Instance.create(InstanceId.of("inst-1")).register(registration);

        HttpHeaders headers = provider.getHeaders(instance);

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(AtengActuatorHttpHeadersProvider.ACTUATOR_TOKEN_HEADER))
                .isEqualTo("instance-specific-token");
    }

    @Test
    @DisplayName("当实例 Metadata 未配置 token 时，保底使用服务端 default-actuator-token")
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
    }

    @Test
    @DisplayName("防御性测试：当 instance 为 null 或 registration 为 null 时，安全返回空 HttpHeaders")
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
