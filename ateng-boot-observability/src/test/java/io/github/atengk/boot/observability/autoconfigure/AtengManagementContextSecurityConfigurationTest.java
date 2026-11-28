package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立管理端口子上下文安全守卫桥接集成测试
 * <p>
 * 基于真实双 Servlet 容器（随机业务端口 + {@code management.server.port=0}），
 * 验证 {@link AtengManagementContextSecurityConfiguration} 对双端口隔离下白名单放行、
 * 高危端点拦截、隐身模式、CIDR 免密、自定义根路径及单端口防重复注册的端到端行为。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-28
 */
@DisplayName("AtengManagementContextSecurityConfiguration 双端口安全桥接集成测试")
class AtengManagementContextSecurityConfigurationTest {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @BeforeEach
    @AfterEach
    void resetStaticSpringContext() {
        // 清理测试容器向 SpringUtil 注入的静态引用，防止关闭后污染同 JVM 后续纯单测
        SpringUtil.clear();
    }

    @Test
    @DisplayName("双端口模式下默认自动桥接安全过滤器：白名单放行、高危与根索引阻断、Token 放行并发布审计事件")
    void shouldProtectChildManagementPortByDefault() throws Exception {
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health,info,prometheus,env",
                "ateng.boot.observability.security.lockdown-enabled=true",
                "ateng.boot.observability.security.token=dual-port-secret",
                "ateng.boot.observability.security.whitelisted-endpoints[0]=health",
                "ateng.boot.observability.security.whitelisted-endpoints[1]=info",
                "ateng.boot.observability.security.whitelisted-endpoints[2]=prometheus"
        )) {
            PortCaptureListener ports = context.getBean(PortCaptureListener.class);
            EventRecorder recorder = context.getBean(EventRecorder.class);

            int serverPort = ports.getServerPort();
            int managementPort = ports.getManagementPort();
            assertThat(serverPort).isPositive();
            assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

            // 1. 业务端口不受影响
            HttpResponse<String> businessResp = sendGet("http://127.0.0.1:" + serverPort + "/api/ping", null);
            assertThat(businessResp.statusCode()).isEqualTo(200);
            assertThat(businessResp.body()).contains("ok");

            // 2. 管理端口白名单端点免密放行
            for (String path : new String[]{
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness",
                    "/actuator/info",
                    "/actuator/prometheus"
            }) {
                HttpResponse<String> resp = sendGet("http://127.0.0.1:" + managementPort + path, null);
                assertThat(resp.statusCode()).as("白名单路径 %s 应免密放行", path).isEqualTo(200);
            }

            // 3. 管理端口根索引与高危端点无凭据返回 403，且主上下文捕获到 ActuatorAccessDeniedEvent
            HttpResponse<String> rootResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator", null);
            assertThat(rootResp.statusCode()).isEqualTo(403);
            assertThat(rootResp.body()).contains("\"code\":403");

            HttpResponse<String> envDeniedResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator/env", null);
            assertThat(envDeniedResp.statusCode()).isEqualTo(403);
            assertThat(envDeniedResp.body()).contains("\"code\":403");

            assertThat(recorder.getDeniedEvents())
                    .extracting(ActuatorAccessDeniedEvent::getUri)
                    .contains("/actuator", "/actuator/env");

            // 4. 携带正确 X-Actuator-Token 放行高危端点
            HttpResponse<String> envAllowedResp = sendGet(
                    "http://127.0.0.1:" + managementPort + "/actuator/env",
                    "dual-port-secret"
            );
            assertThat(envAllowedResp.statusCode()).isEqualTo(200);
            assertThat(envAllowedResp.body()).contains("propertySources");
        }
    }

    @Test
    @DisplayName("双端口模式下开启 stealth-mode=true 时返回 404 隐身阻断并照常发布审计事件，且 CIDR 白名单生效")
    void shouldSupportStealthModeAndCidrWhitelistOnChildManagementPort() throws Exception {
        // 1. stealth-mode=true 且无白名单 IP 时，未授权访问返回 404 并广播审计事件
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.stealth-mode=true",
                "ateng.boot.observability.security.token=stealth-dual-secret",
                "ateng.boot.observability.security.whitelisted-endpoints[0]=health"
        )) {
            int managementPort = context.getBean(PortCaptureListener.class).getManagementPort();
            EventRecorder recorder = context.getBean(EventRecorder.class);

            HttpResponse<String> deniedResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator/env", null);
            assertThat(deniedResp.statusCode()).isEqualTo(404);
            assertThat(deniedResp.body()).contains("\"code\":404").doesNotContain("Actuator");
            assertThat(recorder.getDeniedEvents())
                    .extracting(ActuatorAccessDeniedEvent::getUri)
                    .contains("/actuator/env");
        }

        // 2. 配置 allowed-ip-ranges=127.0.0.1/32 时，本机管理端口免 Token 直接放行高危端点与根索引
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.stealth-mode=true",
                "ateng.boot.observability.security.token=cidr-dual-secret",
                "ateng.boot.observability.security.allowed-ip-ranges[0]=127.0.0.1/32"
        )) {
            int managementPort = context.getBean(PortCaptureListener.class).getManagementPort();

            HttpResponse<String> envResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator/env", null);
            assertThat(envResp.statusCode()).isEqualTo(200);

            HttpResponse<String> rootResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator", null);
            assertThat(rootResp.statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("当 management-context-enabled=false 时，关闭子上下文安全桥接而主上下文 Filter 仍保留")
    void shouldDisableChildManagementFilterWhenPropertyFalse() throws Exception {
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.token=disabled-child-secret",
                "ateng.boot.observability.security.management-context-enabled=false"
        )) {
            assertThat(context.getBeansOfType(ActuatorEndpointSecurityFilter.class)).hasSize(1);
            int managementPort = context.getBean(PortCaptureListener.class).getManagementPort();

            HttpResponse<String> envResp = sendGet("http://127.0.0.1:" + managementPort + "/actuator/env", null);
            assertThat(envResp.statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("双端口模式下支持自定义 management.server.base-path 与 management.endpoints.web.base-path=/")
    void shouldSupportCustomManagementContextAndRootEndpointBasePath() throws Exception {
        // 1. management.server.base-path=/manage + 默认 /actuator
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.server.base-path=/manage",
                "management.endpoints.web.base-path=/actuator",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.token=custom-manage-secret",
                "ateng.boot.observability.security.whitelisted-endpoints[0]=health"
        )) {
            int managementPort = context.getBean(PortCaptureListener.class).getManagementPort();

            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/manage/actuator/health", null).statusCode())
                    .isEqualTo(200);
            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/manage/actuator/env", null).statusCode())
                    .isEqualTo(403);
            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/manage/actuator/env", "custom-manage-secret").statusCode())
                    .isEqualTo(200);
        }

        // 2. management.endpoints.web.base-path=/ 根路径暴露模式
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.base-path=/",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.token=root-endpoint-secret",
                "ateng.boot.observability.security.whitelisted-endpoints[0]=health"
        )) {
            int managementPort = context.getBean(PortCaptureListener.class).getManagementPort();

            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/health", null).statusCode())
                    .isEqualTo(200);
            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/", null).statusCode())
                    .isEqualTo(403);
            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/env", null).statusCode())
                    .isEqualTo(403);
            assertThat(sendGet("http://127.0.0.1:" + managementPort + "/env", "root-endpoint-secret").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("单端口同口模式下不触发子上下文桥接 Bean 注册，避免重复过滤器包装")
    void shouldNotRegisterChildBridgeOnSamePort() throws Exception {
        try (ConfigurableApplicationContext context = startApp(
                "server.port=0",
                "management.endpoints.web.exposure.include=health,env",
                "ateng.boot.observability.security.token=same-port-secret",
                "ateng.boot.observability.security.whitelisted-endpoints[0]=health"
        )) {
            PortCaptureListener ports = context.getBean(PortCaptureListener.class);
            assertThat(ports.getServerPort()).isPositive();
            assertThat(ports.getManagementPort()).isEqualTo(-1);

            assertThat(context.getBeansOfType(ActuatorEndpointSecurityFilter.class)).hasSize(1);
            assertThat(context.containsBean(
                    AtengManagementContextSecurityConfiguration.MANAGEMENT_SECURITY_FILTER_REGISTRATION_BEAN_NAME
            )).isFalse();

            assertThat(sendGet("http://127.0.0.1:" + ports.getServerPort() + "/actuator/health", null).statusCode())
                    .isEqualTo(200);
            assertThat(sendGet("http://127.0.0.1:" + ports.getServerPort() + "/actuator/env", null).statusCode())
                    .isEqualTo(403);
            assertThat(sendGet(
                    "http://127.0.0.1:" + ports.getServerPort() + "/actuator/env",
                    "same-port-secret"
            ).statusCode()).isEqualTo(200);
        }
    }

    private static ConfigurableApplicationContext startApp(String... extraProperties) {
        String[] baseProperties = {
                "spring.main.banner-mode=off",
                "spring.application.name=test-dual-port-app",
                "management.health.defaults.enabled=false",
                "management.health.ping.enabled=true",
                "management.health.livenessstate.enabled=true",
                "management.health.readinessstate.enabled=true",
                "ateng.boot.observability.health.storage.enabled=false",
                "ateng.boot.observability.health.rocketmq.enabled=false",
                "ateng.boot.observability.health.realtime.enabled=false",
                "ateng.boot.observability.health.dynamic-datasource.enabled=false",
                "ateng.boot.observability.health.redis.enabled=false",
                "ateng.boot.observability.health.kafka.enabled=false",
                "ateng.boot.observability.health.rabbitmq.enabled=false",
                "ateng.boot.observability.health.search.enabled=false",
                "ateng.boot.observability.health.mail.enabled=false",
                "ateng.boot.observability.health.sms.enabled=false",
                "ateng.boot.observability.health.ai.enabled=false"
        };
        String[] cliArgs = new String[baseProperties.length + extraProperties.length];
        for (int i = 0; i < baseProperties.length; i++) {
            cliArgs[i] = "--" + baseProperties[i];
        }
        for (int i = 0; i < extraProperties.length; i++) {
            cliArgs[baseProperties.length + i] = "--" + extraProperties[i];
        }
        return new SpringApplicationBuilder(DualPortTestApplication.class)
                .run(cliArgs);
    }

    private static HttpResponse<String> sendGet(String url, String actuatorToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET();
        if (actuatorToken != null) {
            builder.header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, actuatorToken);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "io.github.atengk.boot.storage.autoconfigure.AtengStorageAutoConfiguration",
            "io.github.atengk.boot.mybatisplus.autoconfigure.AtengMybatisPlusAutoConfiguration",
            "io.github.atengk.boot.mybatisplus.autoconfigure.AtengMybatisPlusDynamicQueryAutoConfiguration",
            "io.github.atengk.boot.redis.autoconfigure.AtengRedisAutoConfiguration",
            "io.github.atengk.boot.rabbitmq.autoconfig.AtengRabbitmqAutoConfiguration",
            "io.github.atengk.boot.kafka.autoconfigure.AtengKafkaAutoConfiguration",
            "io.github.atengk.boot.rocketmq.autoconfigure.AtengRocketmqAutoConfiguration",
            "io.github.atengk.boot.realtime.autoconfigure.AtengRealtimeAutoConfiguration",
            "io.github.atengk.boot.search.autoconfigure.AtengSearchAutoConfiguration",
            "io.github.atengk.boot.mail.autoconfigure.AtengMailAutoConfiguration",
            "io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration",
            "io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration"
    })
    static class DualPortTestApplication {

        @RestController
        static class BusinessController {

            @GetMapping("/api/ping")
            Map<String, String> ping() {
                return Map.of("status", "ok");
            }
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }

        @Bean
        PortCaptureListener portCaptureListener() {
            return new PortCaptureListener();
        }
    }

    static class EventRecorder {

        private final List<ActuatorAccessDeniedEvent> deniedEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onAccessDenied(ActuatorAccessDeniedEvent event) {
            deniedEvents.add(event);
        }

        List<ActuatorAccessDeniedEvent> getDeniedEvents() {
            return deniedEvents;
        }
    }

    static class PortCaptureListener implements ApplicationListener<WebServerInitializedEvent> {

        private volatile int serverPort = -1;
        private volatile int managementPort = -1;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            String namespace = event.getApplicationContext().getServerNamespace();
            int port = event.getWebServer().getPort();
            if ("management".equals(namespace)) {
                this.managementPort = port;
            } else {
                this.serverPort = port;
            }
        }

        int getServerPort() {
            return serverPort;
        }

        int getManagementPort() {
            return managementPort;
        }
    }
}
