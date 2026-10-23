package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelProvider;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.channel.NotificationChannelResolver;
import io.github.atengk.boot.notification.event.NotificationChannelEvictEvent;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知多命名渠道注册中心单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("通知渠道注册中心单元测试")
class NotificationChannelRegistryTest {

    private NotificationChannelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NotificationChannelRegistry("default-channel");
    }

    @Test
    @DisplayName("测试基础增删查与清空功能")
    void testBasicCrud() {
        NotificationChannel channel = NotificationChannel.builder()
                .name("ops-alert")
                .platform(Platform.DINGTALK)
                .webhook("https://oapi.dingtalk.com/robot/send?access_token=123")
                .build();

        registry.register(channel);
        assertThat(registry.has("ops-alert")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("ops-alert")).isEqualTo(channel);
        assertThat(registry.getAll()).containsKey("ops-alert");

        // 注销
        NotificationChannel removed = registry.unregister("ops-alert");
        assertThat(removed).isEqualTo(channel);
        assertThat(registry.has("ops-alert")).isFalse();
        assertThat(registry.size()).isEqualTo(0);

        // 清空
        registry.register(channel);
        registry.clear();
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试回退默认渠道")
    void testDefaultChannelFallback() {
        NotificationChannel defaultChannel = NotificationChannel.builder()
                .name("default-channel")
                .platform(Platform.WECOM)
                .webhook("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=123")
                .build();

        registry.register(defaultChannel);

        // 显式查询 null、空白或 "default" 均应命中 default-channel
        assertThat(registry.get(null)).isEqualTo(defaultChannel);
        assertThat(registry.get("")).isEqualTo(defaultChannel);
        assertThat(registry.get("default")).isEqualTo(defaultChannel);
        assertThat(registry.get("DEFAULT")).isEqualTo(defaultChannel);
    }

    @Test
    @DisplayName("测试动态解析器 SPI (NotificationChannelResolver) 介入路由")
    void testDynamicChannelResolver() {
        NotificationChannel tenantChannel = NotificationChannel.builder()
                .name("tenant-001")
                .platform(Platform.FEISHU)
                .webhook("https://open.feishu.cn/open-apis/bot/v2/hook/abc")
                .build();
        registry.register(tenantChannel);

        // 注册解析器，动态返回 tenant-001
        NotificationChannelResolver resolver = () -> "tenant-001";
        registry.addResolver(resolver);

        // 未传渠道名称时，解析器优先于默认渠道
        assertThat(registry.get(null)).isEqualTo(tenantChannel);

        // 移除解析器后回退默认渠道
        registry.removeResolver(resolver);
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(NotificationException.class)
                .extracting(ex -> ((NotificationException) ex).getResultCode())
                .isEqualTo(NotificationResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
    }

    @Test
    @DisplayName("测试动态供给器 SPI (NotificationChannelProvider) 懒加载与自动缓存")
    void testDynamicChannelProviderLazyLoad() {
        NotificationChannel remoteChannel = NotificationChannel.builder()
                .name("remote-db-channel")
                .platform(Platform.GENERIC)
                .webhook("https://api.example.com/webhook")
                .build();

        NotificationChannelProvider provider = name -> {
            if ("remote-db-channel".equals(name)) {
                return remoteChannel;
            }
            return null;
        };

        registry.addProvider(provider);

        // 初始缓存中无此渠道
        assertThat(registry.has("remote-db-channel")).isFalse();

        // 查询时通过 provider 懒加载并自动存入缓存
        NotificationChannel loaded = registry.get("remote-db-channel");
        assertThat(loaded).isEqualTo(remoteChannel);
        assertThat(registry.has("remote-db-channel")).isTrue();
    }

    @Test
    @DisplayName("测试未找到渠道抛出语义化业务异常")
    void testChannelNotFoundException() {
        assertThatThrownBy(() -> registry.get("unknown-channel"))
                .isInstanceOf(NotificationException.class)
                .extracting(ex -> ((NotificationException) ex).getResultCode())
                .isEqualTo(NotificationResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
    }

    @Test
    @DisplayName("测试注销渠道时自动发布 NotificationChannelEvictEvent 事件")
    void testChannelUnregisterPublishesEvictEvent() {
        AtomicReference<NotificationChannelEvictEvent> capturedEvent = new AtomicReference<>();
        registry.setEventPublisher(event -> {
            if (event instanceof NotificationChannelEvictEvent evictEvent) {
                capturedEvent.set(evictEvent);
            }
        });

        NotificationChannel channel = NotificationChannel.builder()
                .name("test-evict-channel")
                .platform(Platform.FEISHU)
                .webhook("https://open.feishu.cn/open-apis/bot/v2/hook/xxx")
                .build();
        registry.register(channel);

        NotificationChannel removed = registry.unregister("test-evict-channel");
        assertThat(removed).isEqualTo(channel);
        assertThat(capturedEvent.get()).isNotNull();
        assertThat(capturedEvent.get().getChannelName()).isEqualTo("test-evict-channel");
        assertThat(capturedEvent.get().getEvictedChannel()).isEqualTo(channel);
    }
}
