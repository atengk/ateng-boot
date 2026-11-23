package io.github.atengk.boot.admin.admission;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminAdmissionInstanceFilter 服务发现与注册过滤桥接器单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminAdmissionInstanceFilter 服务发现准入桥接单测")
class AdminAdmissionInstanceFilterTest {

    @Test
    @DisplayName("source=discovery 合法实例免 HTTP 密钥直接准入，高危 URL 或不合规 CIDR 实例被拦截")
    void shouldFilterDiscoveryAndDirectRegistrationsThroughAdmissionPolicy() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.setClientSecret("CLUSTER_SECRET");
        properties.setAllowedCidrs(List.of("10.20.0.0/16"));
        DefaultAdminInstanceAdmissionPolicy policy = new DefaultAdminInstanceAdmissionPolicy(properties);
        AdminAdmissionInstanceFilter filter = new AdminAdmissionInstanceFilter(policy);

        // 1. 合法 discovery 实例无需 HTTP 密钥头即可通过 InstanceFilter
        Instance validDiscovery = Instance.create(InstanceId.of("disc-ok"))
                .register(Registration.create("order-service", "http://10.20.1.8:8080/actuator/health")
                        .serviceUrl("http://10.20.1.8:8080/")
                        .source("discovery")
                        .build());
        assertThat(filter.filter(validDiscovery)).isTrue();

        // 2. discovery 来源但指向阿里云元数据 100.100.100.200 -> 阻断
        Instance evilDiscovery = Instance.create(InstanceId.of("disc-evil"))
                .register(Registration.create("evil-discovery", "http://100.100.100.200/latest/meta-data/")
                        .serviceUrl("http://100.100.100.200/")
                        .source("discovery")
                        .build());
        assertThat(filter.filter(evilDiscovery)).isFalse();

        // 3. discovery 来源但不在 allowedCidrs -> 阻断
        Instance outsideCidrDiscovery = Instance.create(InstanceId.of("disc-cidr-out"))
                .register(Registration.create("out-cidr", "http://192.168.1.50:8080/actuator/health")
                        .serviceUrl("http://192.168.1.50:8080/")
                        .source("discovery")
                        .build());
        assertThat(filter.filter(outsideCidrDiscovery)).isFalse();

        // 4. http-api 已通过前缀过滤器验密后，InstanceFilter 不因无 HttpServletRequest 误杀合法实例
        Instance validHttpApi = Instance.create(InstanceId.of("http-ok"))
                .register(Registration.create("pay-service", "http://10.20.2.9:8080/actuator/health")
                        .serviceUrl("http://10.20.2.9:8080/")
                        .source("http-api")
                        .build());
        assertThat(filter.filter(validHttpApi)).isTrue();

        // 5. 兜底拦截直接绕过 Filter 写入的高危 http-api 实例
        Instance evilDirect = Instance.create(InstanceId.of("direct-evil"))
                .register(Registration.create("direct-evil", "http://169.254.169.254/latest/meta-data/")
                        .serviceUrl("http://169.254.169.254/")
                        .source("http-api")
                        .build());
        assertThat(filter.filter(evilDirect)).isFalse();
        assertThat(filter.filter(null)).isFalse();
    }
}
