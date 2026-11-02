package io.github.atengk.boot.admin.admission;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultAdminInstanceAdmissionPolicy 单元测试
 * 覆盖密钥核验、169.254 云元数据阻断、本地环回控制与正则白名单过滤
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("DefaultAdminInstanceAdmissionPolicy 默认准入策略单元测试")
class DefaultAdminInstanceAdmissionPolicyTest {

    private static final String VALID_SECRET = "CLUSTER_SECRET_2026";

    private AtengAdminServerProperties createProperties() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setClientSecret(VALID_SECRET);
        properties.setAllowLocalInstances(true);
        return properties;
    }

    @Test
    @DisplayName("合法实例携带匹配密钥与安全URL时评估通过")
    void shouldAdmitValidRegistration() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.builder()
                .name("order-service")
                .serviceUrl("http://order-service:8080/")
                .managementUrl("http://order-service:8081/actuator")
                .healthUrl("http://order-service:8081/actuator/health")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(registration, request);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRejected()).isFalse();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(result.getReason()).isNull();
    }

    @Test
    @DisplayName("客户端未提供通信密钥或密钥不匹配时直接 403 拒绝")
    void shouldRejectWhenSecretMissingOrMismatched() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.of(
                "order-service",
                "http://order-service:8080/",
                "http://order-service:8081/actuator",
                "http://order-service:8081/actuator/health"
        );

        // 1. 无标头
        MockHttpServletRequest requestWithoutSecret = new MockHttpServletRequest("POST", "/instances");
        AdminAdmissionResult res1 = policy.evaluate(registration, requestWithoutSecret);
        assertThat(res1.isRejected()).isTrue();
        assertThat(res1.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res1.getReason()).contains("Invalid X-Admin-Client-Secret");

        // 2. 错误标头
        MockHttpServletRequest requestWithWrongSecret = new MockHttpServletRequest("POST", "/instances");
        requestWithWrongSecret.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, "WRONG");
        AdminAdmissionResult res2 = policy.evaluate(registration, requestWithWrongSecret);
        assertThat(res2.isRejected()).isTrue();
        assertThat(res2.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res2.getReason()).contains("Invalid X-Admin-Client-Secret");
    }

    @Test
    @DisplayName("硬阻断 169.254.169.254 云元数据与 169.254.x.x 链路本地地址")
    void shouldHardBlockCloudMetadataAddress() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.builder()
                .name("evil-app")
                .serviceUrl("http://169.254.169.254/latest/meta-data/")
                .managementUrl("http://169.254.169.254/latest/meta-data/")
                .healthUrl("http://169.254.169.254/latest/meta-data/")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(registration, request);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getReason()).contains("URL forbidden by SSRF Guard Policy");
    }

    @Test
    @DisplayName("当 allowLocalInstances=false 时拦截本地环回地址；为 true 时放行")
    void shouldEnforceLoopbackPolicy() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowLocalInstances(false);
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        InstanceRegistration registration = InstanceRegistration.of(
                "local-app",
                "http://127.0.0.1:8080/",
                "http://127.0.0.1:8080/actuator",
                "http://127.0.0.1:8080/actuator/health"
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult resultBlocked = policy.evaluate(registration, request);
        assertThat(resultBlocked.isRejected()).isTrue();

        // 重新开启环回允许
        properties.setAllowLocalInstances(true);
        AdminAdmissionResult resultAllowed = policy.evaluate(registration, request);
        assertThat(resultAllowed.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("配置 allowed-url-patterns 正则白名单时精确匹配过滤")
    void shouldEnforceAllowedUrlPatterns() {
        AtengAdminServerProperties properties = createProperties();
        properties.setAllowedUrlPatterns(List.of("^https?://.*\\.corp\\.internal(:\\d+)?/.*$"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        // 1. 命中白名单正则
        InstanceRegistration validReg = InstanceRegistration.of(
                "corp-service",
                "http://order.corp.internal:8080/",
                "http://order.corp.internal:8080/actuator",
                "http://order.corp.internal:8080/actuator/health"
        );
        assertThat(policy.evaluate(validReg, request).isAllowed()).isTrue();

        // 2. 未命中白名单正则
        InstanceRegistration invalidReg = InstanceRegistration.of(
                "external-service",
                "http://unknown-host.com:8080/",
                "http://unknown-host.com:8080/actuator",
                "http://unknown-host.com:8080/actuator/health"
        );
        assertThat(policy.evaluate(invalidReg, request).isRejected()).isTrue();
    }

    @Test
    @DisplayName("支持 null registration 弹性审查")
    void shouldHandleNullRegistration() {
        AtengAdminServerProperties properties = createProperties();
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances");
        request.addHeader(DefaultAdminInstanceAdmissionPolicy.CLIENT_SECRET_HEADER, VALID_SECRET);

        AdminAdmissionResult result = policy.evaluate(null, request);
        assertThat(result.isAllowed()).isTrue();
    }
}
