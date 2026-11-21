package io.github.atengk.boot.admin.autoconfigure;

import cn.dev33.satoken.filter.SaServletFilter;
import de.codecentric.boot.admin.server.config.AdminServerMarkerConfiguration;
import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.controller.AdminLoginController;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminAuthProvider;
import io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProvider;
import io.github.atengk.boot.admin.provider.DefaultPropertiesAdminAuthProvider;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.admin.security.AdminSsrfGuardFilter;
import io.github.atengk.boot.admin.security.AdminWriteGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

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

        @Bean
        public AdminServerProperties adminServerProperties() {
            return new AdminServerProperties();
        }

        @Bean
        public InstanceRepository instanceRepository() {
            return org.mockito.Mockito.mock(InstanceRepository.class);
        }
    }

    @Configuration
    static class CustomAuthProviderConfiguration {
        @Bean
        public AdminAuthProvider customAdminAuthProvider() {
            return (user, pass) -> "custom-user".equals(user) && "custom-pass".equals(pass);
        }
    }

    @Configuration
    static class CustomAdmissionPolicyConfiguration {
        @Bean
        public AdminInstanceAdmissionPolicy customAdmissionPolicy() {
            return (registration, request) -> AdminAdmissionResult.admit();
        }
    }

    @Configuration
    static class CustomCleanupSchedulerConfiguration {
        @Bean
        public ZombieInstanceCleanupScheduler customZombieInstanceCleanupScheduler() {
            return new ZombieInstanceCleanupScheduler(null, null, new AtengAdminServerProperties());
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
                    assertThat(properties.getMaxRegistrationPayloadSize()).isEqualTo(DataSize.ofMegabytes(1));
                    assertThat(properties.getCleanup().isEnabled()).isFalse();
                    assertThat(properties.getCleanup().getInterval()).isEqualTo(Duration.ofHours(1));
                    assertThat(properties.getCleanup().getZombieThreshold()).isEqualTo(Duration.ofHours(24));
                    assertThat(properties.getAlert().getIncludedServices()).isEmpty();
                    assertThat(properties.getAlert().getExcludedServices()).isEmpty();
                    assertThat(properties.getAlert().getNotifyStatuses()).containsExactly("DOWN", "OFFLINE");

                    // 验证认证与登录组件自动装配
                    assertThat(context).hasSingleBean(AdminAuthProvider.class);
                    assertThat(context.getBean(AdminAuthProvider.class)).isInstanceOf(DefaultPropertiesAdminAuthProvider.class);
                    assertThat(context).hasSingleBean(AdminLoginController.class);
                    assertThat(context).hasSingleBean(AtengActuatorHttpHeadersProvider.class);
                    assertThat(context).hasSingleBean(AdminInstanceAdmissionPolicy.class);
                    assertThat(context.getBean(AdminInstanceAdmissionPolicy.class)).isInstanceOf(DefaultAdminInstanceAdmissionPolicy.class);
                    assertThat(context).hasSingleBean(AdminSsrfGuardFilter.class);
                    assertThat(context).hasSingleBean(AdminWriteGuardFilter.class);
                    assertThat(context).hasSingleBean(AtengAdminNotificationNotifier.class);
                    assertThat(context).hasBean("adminSaServletFilter");
                    assertThat(context.getBean("adminSaServletFilter")).isInstanceOf(SaServletFilter.class);
                    assertThat(context).doesNotHaveBean(ZombieInstanceCleanupScheduler.class);
                });
    }

    @Test
    @DisplayName("开发者声明自定义 AdminAuthProvider 时能够平滑覆盖默认实现")
    void shouldAllowCustomAdminAuthProviderOverride() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class, CustomAuthProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AdminAuthProvider.class);
                    AdminAuthProvider authProvider = context.getBean(AdminAuthProvider.class);
                    assertThat(authProvider).isNotInstanceOf(DefaultPropertiesAdminAuthProvider.class);
                    assertThat(authProvider.authenticate("custom-user", "custom-pass")).isTrue();
                    assertThat(authProvider.authenticate("admin", "admin")).isFalse();
                });
    }

    @Test
    @DisplayName("开发者声明自定义 AdminInstanceAdmissionPolicy 时能够平滑覆盖默认实现")
    void shouldAllowCustomAdmissionPolicyOverride() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class, CustomAdmissionPolicyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AdminInstanceAdmissionPolicy.class);
                    AdminInstanceAdmissionPolicy admissionPolicy = context.getBean(AdminInstanceAdmissionPolicy.class);
                    assertThat(admissionPolicy).isNotInstanceOf(DefaultAdminInstanceAdmissionPolicy.class);
                });
    }

    @Test
    @DisplayName("开发者声明自定义 ZombieInstanceCleanupScheduler 时能够平滑覆盖默认装配")
    void shouldAllowCustomCleanupSchedulerOverride() {
        webContextRunner.withUserConfiguration(MarkerTestConfiguration.class, CustomCleanupSchedulerConfiguration.class)
                .withPropertyValues("ateng.boot.admin.server.cleanup.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ZombieInstanceCleanupScheduler.class);
                    assertThat(context.getBean(ZombieInstanceCleanupScheduler.class))
                            .isSameAs(context.getBean("customZombieInstanceCleanupScheduler"));
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
                        "ateng.boot.admin.server.allowed-cidrs[0]=10.20.0.0/16",
                        "ateng.boot.admin.server.blocked-cidrs[0]=10.20.99.0/24",
                        "ateng.boot.admin.server.block-private-networks=true",
                        "ateng.boot.admin.server.dns-resolution-mode=FAIL_CLOSED",
                        "ateng.boot.admin.server.outbound-ssrf-check-enabled=false",
                        "ateng.boot.admin.server.actuator-token-header=X-Custom-Actuator-Token",
                        "ateng.boot.admin.server.service-actuator-tokens.order-service=svc-token-01",
                        "ateng.boot.admin.server.enable-write-operations=false",
                        "ateng.boot.admin.server.max-registration-payload-size=512KB",
                        "ateng.boot.admin.server.cleanup.enabled=true",
                        "ateng.boot.admin.server.cleanup.interval=30m",
                        "ateng.boot.admin.server.cleanup.zombie-threshold=12h",
                        "ateng.boot.admin.server.alert.included-services[0]=order-*",
                        "ateng.boot.admin.server.alert.excluded-services[0]=temp-*",
                        "ateng.boot.admin.server.alert.notify-statuses[0]=DOWN"
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
                    assertThat(properties.getAllowedCidrs()).containsExactly("10.20.0.0/16");
                    assertThat(properties.getBlockedCidrs()).containsExactly("10.20.99.0/24");
                    assertThat(properties.isBlockPrivateNetworks()).isTrue();
                    assertThat(properties.getDnsResolutionMode())
                            .isEqualTo(io.github.atengk.boot.admin.properties.DnsResolutionMode.FAIL_CLOSED);
                    assertThat(properties.isOutboundSsrfCheckEnabled()).isFalse();
                    assertThat(properties.getActuatorTokenHeader()).isEqualTo("X-Custom-Actuator-Token");
                    assertThat(properties.getServiceActuatorTokens()).containsEntry("order-service", "svc-token-01");
                    assertThat(properties.isEnableWriteOperations()).isFalse();
                    assertThat(properties.getMaxRegistrationPayloadSize()).isEqualTo(DataSize.ofKilobytes(512));
                    assertThat(properties.getCleanup().isEnabled()).isTrue();
                    assertThat(properties.getCleanup().getInterval()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(properties.getCleanup().getZombieThreshold()).isEqualTo(Duration.ofHours(12));
                    assertThat(properties.getAlert().getIncludedServices()).containsExactly("order-*");
                    assertThat(properties.getAlert().getExcludedServices()).containsExactly("temp-*");
                    assertThat(properties.getAlert().getNotifyStatuses()).containsExactly("DOWN");
                    assertThat(context).hasSingleBean(ZombieInstanceCleanupScheduler.class);
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
                    assertThat(context).doesNotHaveBean(AdminAuthProvider.class);
                    assertThat(context).doesNotHaveBean(AdminLoginController.class);
                    assertThat(context).doesNotHaveBean("adminSaServletFilter");
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

    @Test
    @DisplayName("normalizeContextPath 能够严密防御 null、空白、无前导斜杠及多余末尾斜杠")
    void shouldNormalizeContextPathCorrectly() {
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath(null)).isEmpty();
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("")).isEmpty();
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("   ")).isEmpty();
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("/")).isEmpty();
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("admin")).isEqualTo("/admin");
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("/admin")).isEqualTo("/admin");
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("/admin/")).isEqualTo("/admin");
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("/admin///")).isEqualTo("/admin");
        assertThat(AtengAdminServerAutoConfiguration.normalizeContextPath("  /sba/  ")).isEqualTo("/sba");
    }

    @Test
    @DisplayName("完整装配 SBA 与 Ateng 防线配置后，InstanceFilter、出站 SSRF 过滤器与去重 HttpHeadersProvider 正常协同且不产出重复 Authorization 标头")
    void shouldWireDefenseBeansAndSuppressDuplicateBasicAuthHeaderWithSba() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
                        AtengAdminServerDefenseAutoConfiguration.class,
                        de.codecentric.boot.admin.server.config.AdminServerAutoConfiguration.class,
                        AtengAdminServerAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration.class
                ))
                .withBean(AdminServerMarkerConfiguration.Marker.class, AdminServerMarkerConfiguration.Marker::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(de.codecentric.boot.admin.server.services.InstanceFilter.class);
                    assertThat(context.getBean(de.codecentric.boot.admin.server.services.InstanceFilter.class))
                            .isInstanceOf(io.github.atengk.boot.admin.admission.AdminAdmissionInstanceFilter.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.admin.security.AdminOutboundSsrfFilterFunction.class);
                    assertThat(context.getBean(io.github.atengk.boot.admin.security.AdminOutboundSsrfFilterFunction.class).getOrder())
                            .isEqualTo(io.github.atengk.boot.admin.security.AdminOutboundSsrfFilterFunction.DEFAULT_ORDER);
                    assertThat(context.getBean(AdminSsrfGuardFilter.class).getOrder())
                            .isEqualTo(AdminSsrfGuardFilter.DEFAULT_ORDER);
                    assertThat(context).hasSingleBean(de.codecentric.boot.admin.server.web.client.BasicAuthHttpHeaderProvider.class);

                    AtengAdminServerProperties defaultProps = context.getBean(AtengAdminServerProperties.class);
                    assertThat(defaultProps.getActuatorTokenHeader()).isEqualTo("X-Actuator-Token");
                    assertThat(defaultProps.getDnsResolutionMode())
                            .isEqualTo(io.github.atengk.boot.admin.properties.DnsResolutionMode.FAIL_OPEN);
                    assertThat(defaultProps.isOutboundSsrfCheckEnabled()).isTrue();
                    assertThat(defaultProps.isBlockPrivateNetworks()).isFalse();

                    de.codecentric.boot.admin.server.web.client.CompositeHttpHeadersProvider composite =
                            new de.codecentric.boot.admin.server.web.client.CompositeHttpHeadersProvider(
                                    context.getBeansOfType(de.codecentric.boot.admin.server.web.client.HttpHeadersProvider.class).values()
                            );
                    de.codecentric.boot.admin.server.domain.entities.Instance instance =
                            de.codecentric.boot.admin.server.domain.entities.Instance.create(
                                            de.codecentric.boot.admin.server.domain.values.InstanceId.of("dup-check"))
                                    .register(de.codecentric.boot.admin.server.domain.values.Registration
                                            .create("svc-auth", "http://10.20.1.5:8080/actuator/health")
                                            .managementUrl("http://10.20.1.5:8080/actuator")
                                            .metadata("user.name", "ops")
                                            .metadata("user.password", "pwd123")
                                            .build());
                    org.springframework.http.HttpHeaders headers = composite.getHeaders(instance);
                    assertThat(headers.get(org.springframework.http.HttpHeaders.AUTHORIZATION)).hasSize(1);

                    // 验证真实 SBA filterInstanceWebClientCustomizer 在 Spring 容器 @Order 排序下，
                    // 先由 rewriteEndpointUrl(@Order(10)) 将相对端点 "health" 改写为绝对 URL，再通过出站 SSRF 校验(@Order(15))
                    de.codecentric.boot.admin.server.web.client.InstanceWebClientCustomizer customizer =
                            context.getBean("filterInstanceWebClientCustomizer",
                                    de.codecentric.boot.admin.server.web.client.InstanceWebClientCustomizer.class);
                    org.springframework.web.reactive.function.client.WebClient.Builder stubBuilder =
                            org.springframework.web.reactive.function.client.WebClient.builder()
                                    .exchangeFunction(req -> reactor.core.publisher.Mono.just(
                                            org.springframework.web.reactive.function.client.ClientResponse
                                                    .create(org.springframework.http.HttpStatus.OK).build()));
                    de.codecentric.boot.admin.server.web.client.InstanceWebClient.Builder clientBuilder =
                            de.codecentric.boot.admin.server.web.client.InstanceWebClient.builder(stubBuilder);
                    customizer.customize(clientBuilder);
                    de.codecentric.boot.admin.server.web.client.InstanceWebClient client = clientBuilder.build();
                    assertThat(client.instance(instance).get().uri("health")
                            .exchangeToMono(reactor.core.publisher.Mono::just).block().statusCode())
                            .isEqualTo(org.springframework.http.HttpStatus.OK);
                });
    }
}
