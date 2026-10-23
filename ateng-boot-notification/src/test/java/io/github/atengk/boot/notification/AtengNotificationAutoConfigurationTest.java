package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.autoconfigure.AtengNotificationAutoConfiguration;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.limiter.InMemoryNotificationRateLimiter;
import io.github.atengk.boot.notification.limiter.NotificationRateLimiter;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.provider.DingTalkNotificationProvider;
import io.github.atengk.boot.notification.provider.FeishuNotificationProvider;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.provider.WeComNotificationProvider;
import io.github.atengk.boot.notification.renderer.NotificationTemplateRenderer;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 协同通知自动装配防御性逻辑单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class AtengNotificationAutoConfigurationTest extends BaseNotificationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengNotificationAutoConfiguration.class));

    @Test
    @DisplayName("验证默认配置下自动装配生效并成功注册专有 RestClient、注册中心与驱动 Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationProperties.class);
            assertThat(context).hasBean("notificationClientHttpRequestFactory");
            assertThat(context).hasBean("notificationRestClient");
            assertThat(context).hasSingleBean(NotificationChannelRegistry.class);
            assertThat(context).hasSingleBean(NotificationRateLimiter.class);
            assertInstanceOf(InMemoryNotificationRateLimiter.class, context.getBean(NotificationRateLimiter.class));
            assertThat(context).hasSingleBean(DingTalkNotificationProvider.class);
            assertThat(context).hasSingleBean(WeComNotificationProvider.class);
            assertThat(context).hasSingleBean(FeishuNotificationProvider.class);
            assertThat(context).hasSingleBean(GenericWebhookNotificationProvider.class);
            assertThat(context).hasSingleBean(NotificationTemplateRenderer.class);
            assertThat(context).hasSingleBean(NotificationSendFailureHandler.class);
            assertThat(context).hasSingleBean(NotificationTemplate.class);

            NotificationProperties properties = context.getBean(NotificationProperties.class);
            assertEquals("ops-alert", properties.getDefaultChannel());
            assertEquals(Duration.ofSeconds(3), properties.getConnectTimeout());
            assertEquals(Duration.ofSeconds(5), properties.getReadTimeout());
        });
    }

    @Test
    @DisplayName("验证 ateng.boot.notification.enabled=false 时自动装配条件安全跳过")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.notification.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NotificationProperties.class);
                    assertThat(context).doesNotHaveBean("notificationClientHttpRequestFactory");
                    assertThat(context).doesNotHaveBean("notificationRestClient");
                });
    }

    @Test
    @DisplayName("验证 YAML 配置属性与强类型 Duration 绑定成功")
    void testPropertiesBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.notification.default-channel=finance-notice",
                        "ateng.boot.notification.connect-timeout=2s",
                        "ateng.boot.notification.read-timeout=8s",
                        "ateng.boot.notification.rate-limit.enabled=true",
                        "ateng.boot.notification.rate-limit.max-wait-duration=1500ms",
                        "ateng.boot.notification.channels.ops-alert.platform=DINGTALK",
                        "ateng.boot.notification.channels.ops-alert.webhook=https://oapi.dingtalk.com/robot/send?access_token=test_token",
                        "ateng.boot.notification.channels.ops-alert.secret=test_secret",
                        "ateng.boot.notification.channels.ops-alert.permits-per-minute=20"
                )
                .run(context -> {
                    NotificationProperties properties = context.getBean(NotificationProperties.class);
                    assertEquals("finance-notice", properties.getDefaultChannel());
                    assertEquals(Duration.ofSeconds(2), properties.getConnectTimeout());
                    assertEquals(Duration.ofSeconds(8), properties.getReadTimeout());
                    assertEquals(Duration.ofMillis(1500), properties.getRateLimit().getMaxWaitDuration());

                    assertEquals(1, properties.getChannels().size());
                    assertTrue(properties.getChannels().containsKey("ops-alert"));
                    assertEquals(20, properties.getChannels().get("ops-alert").getPermitsPerMinute());

                    NotificationChannelRegistry registry = context.getBean(NotificationChannelRegistry.class);
                    assertTrue(registry.has("ops-alert"));
                });
    }

    @Test
    @DisplayName("验证 downstream 开启 distributed=true 时未注入分布式 Bean 则平滑回退单机内存版")
    void testDistributedRateLimiterFallback() {
        contextRunner
                .withPropertyValues("ateng.boot.notification.rate-limit.distributed=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationRateLimiter.class);
                    assertInstanceOf(InMemoryNotificationRateLimiter.class, context.getBean(NotificationRateLimiter.class));
                });
    }

    @Test
    @DisplayName("验证下游开发者声明同名 Bean 时平滑无缝接管 (@ConditionalOnMissingBean)")
    void testCustomBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomNotificationBeanConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("notificationClientHttpRequestFactory");
                    ClientHttpRequestFactory factory = context.getBean("notificationClientHttpRequestFactory", ClientHttpRequestFactory.class);
                    assertInstanceOf(SimpleClientHttpRequestFactory.class, factory);

                    RestClient restClient = context.getBean("notificationRestClient", RestClient.class);
                    assertNotNull(restClient);

                    NotificationRateLimiter rateLimiter = context.getBean(NotificationRateLimiter.class);
                    assertNotNull(rateLimiter);
                    assertTrue(rateLimiter instanceof CustomTestRateLimiter);
                });
    }

    @Test
    @DisplayName("验证自动装配的 notificationRestClient 能够对 MockWebServer 执行端到端 HTTP 通信")
    void testRestClientCommunicationWithMockWebServer() {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        contextRunner.run(context -> {
            RestClient restClient = context.getBean("notificationRestClient", RestClient.class);
            assertNotNull(restClient);

            String response = restClient.post()
                    .uri(getWebhookUrl("robot/send"))
                    .body("{\"msgtype\":\"text\",\"text\":{\"content\":\"ping\"}}")
                    .retrieve()
                    .body(String.class);

            assertNotNull(response);
            assertTrue(response.contains("\"errcode\":0"));

            RecordedRequest recordedRequest = takeRequest();
            assertNotNull(recordedRequest);
            assertEquals("POST", recordedRequest.getMethod());
            assertEquals("/robot/send", recordedRequest.getPath());
            assertEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"ping\"}}", recordedRequest.getBody().readUtf8());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomNotificationBeanConfiguration {

        @Bean(name = "notificationClientHttpRequestFactory")
        public ClientHttpRequestFactory customRequestFactory() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1000);
            factory.setReadTimeout(2000);
            return factory;
        }

        @Bean
        public NotificationRateLimiter customNotificationRateLimiter() {
            return new CustomTestRateLimiter();
        }
    }

    static class CustomTestRateLimiter implements NotificationRateLimiter {
        @Override
        public boolean tryAcquire(io.github.atengk.boot.notification.channel.NotificationChannel channel, Duration maxWaitDuration) {
            return true;
        }

        @Override
        public int resolvePermitsPerMinute(io.github.atengk.boot.notification.channel.NotificationChannel channel) {
            return 999;
        }
    }
}
