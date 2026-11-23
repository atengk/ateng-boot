package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.aspect.NotifyAlertAspect;
import io.github.atengk.boot.notification.autoconfigure.AtengNotificationAutoConfiguration;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.limiter.InMemoryNotificationRateLimiter;
import io.github.atengk.boot.notification.limiter.NotificationRateLimiter;
import io.github.atengk.boot.notification.limiter.RedisNotificationRateLimiter;
import io.github.atengk.boot.notification.model.TextMessage;
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
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

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
            assertThat(context).hasBean("notificationAlertTaskScheduler");
            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler alertScheduler =
                    context.getBean("notificationAlertTaskScheduler",
                            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
            assertEquals("ateng-notif-alert-", alertScheduler.getThreadNamePrefix());
            assertThat(context).hasSingleBean(AlertStormSuppressor.class);
            assertThat(context).hasSingleBean(NotifyAlertAspect.class);

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
                        "ateng.boot.notification.alert.detail-url=https://apm.example.com/trace/${traceId}",
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
                    assertEquals("https://apm.example.com/trace/${traceId}", properties.getAlert().getDetailUrl());

                    assertEquals(1, properties.getChannels().size());
                    assertTrue(properties.getChannels().containsKey("ops-alert"));
                    assertEquals(20, properties.getChannels().get("ops-alert").getPermitsPerMinute());

                    NotificationChannelRegistry registry = context.getBean(NotificationChannelRegistry.class);
                    assertTrue(registry.has("ops-alert"));
                });
    }

    @Test
    @DisplayName("验证 Spring 容器关闭时受管 notificationAlertTaskScheduler 同步优雅停机销毁")
    void testAlertSchedulerLifecycleShutdownWithContainer() {
        AtomicReference<org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler> capturedScheduler =
                new AtomicReference<>();
        contextRunner.run(context -> {
            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                    context.getBean("notificationAlertTaskScheduler",
                            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
            capturedScheduler.set(scheduler);
            assertFalse(scheduler.getScheduledThreadPoolExecutor().isShutdown());
        });
        assertNotNull(capturedScheduler.get());
        assertTrue(capturedScheduler.get().getScheduledThreadPoolExecutor().isShutdown());
    }

    @Test
    @DisplayName("验证 notificationAlertTaskScheduler 不干扰其他模块按类型注入单例 TaskScheduler 或 ThreadPoolTaskScheduler")
    void testAlertSchedulerDoesNotPolluteByTypeAutowiring() {
        contextRunner
                .withUserConfiguration(PeerTaskSchedulerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("notificationAlertTaskScheduler");
                    assertThat(context).hasBean("peerTaskScheduler");
                    assertThat(context.getBeanProvider(ThreadPoolTaskScheduler.class).getIfAvailable())
                            .isSameAs(context.getBean("peerTaskScheduler", ThreadPoolTaskScheduler.class));
                    assertThat(context.getBeanProvider(org.springframework.scheduling.TaskScheduler.class).getIfAvailable())
                            .isSameAs(context.getBean("peerTaskScheduler", org.springframework.scheduling.TaskScheduler.class));
                    assertThat(context).hasSingleBean(AlertStormSuppressor.class);
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
    @DisplayName("验证分布式与单机频控条件装配矩阵：开关控制、StringRedisTemplate Bean 就绪、缺类平滑降级与自定义 Bean 退让")
    void testRateLimiterConditionalAssemblyMatrix() {
        // 1. 存在 StringRedisTemplate Bean 但未开启 distributed=true（默认 false）：仍装配单机版
        contextRunner
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationRateLimiter.class);
                    assertInstanceOf(InMemoryNotificationRateLimiter.class, context.getBean(NotificationRateLimiter.class));
                });

        // 2. 开启 distributed=true 且容器存在 StringRedisTemplate Bean：激活 RedisNotificationRateLimiter 并绑定 Redis 属性
        contextRunner
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues(
                        "ateng.boot.notification.rate-limit.distributed=true",
                        "ateng.boot.notification.rate-limit.redis-key-prefix=custom:notify:rl:",
                        "ateng.boot.notification.rate-limit.fail-open-on-redis-error=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationRateLimiter.class);
                    NotificationRateLimiter bean = context.getBean(NotificationRateLimiter.class);
                    assertInstanceOf(RedisNotificationRateLimiter.class, bean);

                    RedisNotificationRateLimiter redisLimiter = (RedisNotificationRateLimiter) bean;
                    assertEquals("custom:notify:rl:{ops}:minute", redisLimiter.buildMinuteKey("ops"));

                    NotificationProperties props = context.getBean(NotificationProperties.class);
                    assertEquals("custom:notify:rl:", props.getRateLimit().getRedisKeyPrefix());
                    assertFalse(props.getRateLimit().isFailOpenOnRedisError());
                });

        // 3. 开启 distributed=true 但类路径缺少 StringRedisTemplate（可选 Redis 依赖未引入）：平滑降级单机版零报错
        contextRunner
                .withClassLoader(new FilteredClassLoader(StringRedisTemplate.class))
                .withPropertyValues("ateng.boot.notification.rate-limit.distributed=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationRateLimiter.class);
                    assertInstanceOf(InMemoryNotificationRateLimiter.class, context.getBean(NotificationRateLimiter.class));
                });

        // 4. 开启 distributed=true 且存在 StringRedisTemplate，但用户自定义了 NotificationRateLimiter：用户 Bean 优先接管
        contextRunner
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues("ateng.boot.notification.rate-limit.distributed=true")
                .withUserConfiguration(CustomNotificationBeanConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationRateLimiter.class);
                    assertInstanceOf(CustomTestRateLimiter.class, context.getBean(NotificationRateLimiter.class));
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

                    org.springframework.scheduling.TaskScheduler scheduler =
                            context.getBean("notificationAlertTaskScheduler", org.springframework.scheduling.TaskScheduler.class);
                    assertInstanceOf(CustomTestTaskScheduler.class, scheduler);
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

    @Test
    @DisplayName("验证渠道注销触发 NotificationChannelEvictEvent 后联动驱逐驱动定制 RestClient 缓存")
    void testChannelEvictEventClearsProviderClientCache() {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        contextRunner
                .withPropertyValues(
                        "ateng.boot.notification.channels.evict-test-channel.platform=DINGTALK",
                        "ateng.boot.notification.channels.evict-test-channel.webhook=" + getWebhookUrl("dingtalk/evict"),
                        "ateng.boot.notification.channels.evict-test-channel.connect-timeout=1500ms",
                        "ateng.boot.notification.channels.evict-test-channel.read-timeout=2500ms"
                )
                .run(context -> {
                    NotificationTemplate template = context.getBean(NotificationTemplate.class);
                    NotificationChannelRegistry registry = context.getBean(NotificationChannelRegistry.class);
                    DingTalkNotificationProvider provider = context.getBean(DingTalkNotificationProvider.class);

                    assertEquals(0, provider.getCustomClientCacheSize());
                    template.send("evict-test-channel", TextMessage.of("触发定制超时客户端缓存"));
                    assertEquals(1, provider.getCustomClientCacheSize());

                    // 热更新同渠道超时配置：触发驱逐事件并原位替换缓存，严禁条目数膨胀
                    enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");
                    registry.register(io.github.atengk.boot.notification.channel.NotificationChannel.builder()
                            .name("evict-test-channel")
                            .platform(io.github.atengk.boot.notification.model.Platform.DINGTALK)
                            .webhook(getWebhookUrl("dingtalk/evict"))
                            .connectTimeout(Duration.ofMillis(1800))
                            .readTimeout(Duration.ofMillis(2800))
                            .build());
                    assertEquals(0, provider.getCustomClientCacheSize());
                    template.send("evict-test-channel", TextMessage.of("触发热更新后新超时缓存"));
                    assertEquals(1, provider.getCustomClientCacheSize());

                    registry.unregister("evict-test-channel");
                    assertEquals(0, provider.getCustomClientCacheSize());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PeerTaskSchedulerConfiguration {

        @Bean(name = "peerTaskScheduler", destroyMethod = "destroy")
        public ThreadPoolTaskScheduler peerTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("peer-sched-");
            scheduler.setDaemon(true);
            return scheduler;
        }
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

        @Bean(name = "notificationAlertTaskScheduler")
        public org.springframework.scheduling.TaskScheduler customNotificationAlertTaskScheduler() {
            return new CustomTestTaskScheduler();
        }
    }

    static class CustomTestTaskScheduler extends ThreadPoolTaskScheduler {
        CustomTestTaskScheduler() {
            setPoolSize(1);
            setThreadNamePrefix("custom-alert-sched-");
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
