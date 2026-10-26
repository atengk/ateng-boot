package io.github.atengk.boot.admin.autoconfigure;

import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengAdminServerAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("Spring Boot Admin Server 自动装配单元测试")
class AtengAdminServerAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAdminServerAutoConfiguration.class));

    private final ApplicationContextRunner nonWebContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAdminServerAutoConfiguration.class));

    @Configuration
    static class MarkerTestConfiguration {
        @Bean
        public AdminServerMarkerConfiguration.Marker adminServerMarker() {
            return new AdminServerMarkerConfiguration.Marker();
        }
    }

    @Test
    @DisplayName("存在 Marker 且默认配置下成功装配 AtengAdminServerAutoConfiguration 并具备合理默认值")
    void shouldAutoConfigureWhenMarkerPresentWithDefaults() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengAdminServerAutoConfiguration.class);
                    assertThat(context).hasSingleBean(AtengAdminServerProperties.class);

                    AtengAdminServerProperties properties = context.getBean(AtengAdminServerProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getUsername()).isEqualTo("admin");
                    assertThat(properties.getPassword()).isEqualTo("admin");
                    assertThat(properties.getClientSecret()).isEqualTo("YOUR_CLIENT_SECRET");
                    assertThat(properties.getDefaultActuatorToken()).isEmpty();
                    assertThat(properties.getDebounceDuration()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.isAllowLocalInstances()).isTrue();
                    assertThat(properties.getAllowedUrlPatterns()).isEmpty();
                    assertThat(properties.isEnableWriteOperations()).isTrue();
                });
    }

    @Test
    @DisplayName("自定义配置属性能够正确绑定并生效")
    void shouldBindCustomPropertiesSuccessfully() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.admin.server.username=ops-user",
                        "ateng.boot.admin.server.password=secretPass!",
                        "ateng.boot.admin.server.client-secret=clusterSecret123",
                        "ateng.boot.admin.server.default-actuator-token=tokenXYZ",
                        "ateng.boot.admin.server.debounce-duration=45s",
                        "ateng.boot.admin.server.allow-local-instances=false",
                        "ateng.boot.admin.server.allowed-url-patterns[0]=^https://.*",
                        "ateng.boot.admin.server.enable-write-operations=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengAdminServerProperties.class);
                    AtengAdminServerProperties properties = context.getBean(AtengAdminServerProperties.class);
                    assertThat(properties.getUsername()).isEqualTo("ops-user");
                    assertThat(properties.getPassword()).isEqualTo("secretPass!");
                    assertThat(properties.getClientSecret()).isEqualTo("clusterSecret123");
                    assertThat(properties.getDefaultActuatorToken()).isEqualTo("tokenXYZ");
                    assertThat(properties.getDebounceDuration()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.isAllowLocalInstances()).isFalse();
                    assertThat(properties.getAllowedUrlPatterns()).containsExactly("^https://.*");
                    assertThat(properties.isEnableWriteOperations()).isFalse();
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.admin.server.enabled=false 时不触发自动装配")
    void shouldNotConfigureWhenDisabled() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class)
                .withPropertyValues("ateng.boot.admin.server.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengAdminServerAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengAdminServerProperties.class);
                });
    }

    @Test
    @DisplayName("缺少 AdminServerMarkerConfiguration.Marker Bean 时不触发装配")
    void shouldNotConfigureWhenMarkerMissing() {
        webContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtengAdminServerAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(AtengAdminServerProperties.class);
        });
    }

    @Test
    @DisplayName("非 Web 环境下即使存在 Marker 也不触发装配")
    void shouldNotConfigureInNonWebApplication() {
        nonWebContextRunner.withUserConfiguration(MarkerTestConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengAdminServerAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengAdminServerProperties.class);
                });
    }
}
