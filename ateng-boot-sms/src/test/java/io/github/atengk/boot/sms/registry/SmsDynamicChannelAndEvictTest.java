package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.event.SmsChannelEvictEvent;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.listener.SmsChannelEvictListener;
import io.github.atengk.boot.sms.loadbalancer.SmsChannelLoadBalancer;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 工单 #1148 动态渠道按需供给、有界 TTL LRU 缓存与集群热驱逐测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 动态渠道供给与热驱逐测试")
class SmsDynamicChannelAndEvictTest {

    @Test
    @DisplayName("SmsChannelProvider 按需懒加载与 LRU 上限及 Clock TTL 过期重加载")
    void shouldLoadDynamicChannelWithBoundedLruAndTtlExpiry() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-21T08:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };

        SmsProperties props = new SmsProperties();
        props.getRegistry().setDynamicCacheMaxSize(2);
        props.getRegistry().setDynamicCacheTtl(Duration.ofMinutes(10));

        Map<String, AtomicInteger> loadCounts = new ConcurrentHashMap<>();
        AtomicReference<String> signVersion = new AtomicReference<>("V1");
        SmsChannelProvider provider = name -> {
            if (!name.startsWith("tenant-")) {
                return null;
            }
            loadCounts.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            return SmsChannel.builder()
                    .name(name)
                    .supplier(SmsSupplier.MOCK)
                    .signName(signVersion.get())
                    .defaultTemplateId("TPL_DYN")
                    .build();
        };

        SmsChannelRegistry registry = new SmsChannelRegistry(props, provider, mutableClock);
        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());
        SmsTemplate template = new SmsTemplate(props, List.of(mockProvider), registry, null, null, null, null, null);

        // 1. 首次按需加载并缓存命中
        SmsResult r1 = template.send(SmsRequest.of("13800138000", "TPL_DYN").withChannel("tenant-a"));
        assertThat(r1.isSuccess()).isTrue();
        SmsResult r2 = template.send(SmsRequest.of("13800138001", "TPL_DYN").withChannel("tenant-a"));
        assertThat(r2.isSuccess()).isTrue();
        assertThat(loadCounts.get("tenant-a").get()).isEqualTo(1);

        // 2. 加载 tenant-b 和 tenant-c，触发 LRU 上限 (2) 淘汰最久未访问的 tenant-a
        assertThat(registry.get("tenant-b")).isNotNull();
        assertThat(registry.get("tenant-c")).isNotNull();
        assertThat(registry.dynamicSize()).isEqualTo(2);

        // 重新访问 tenant-a 时应触发重新加载
        signVersion.set("V2");
        SmsChannel reloadedA = registry.get("tenant-a");
        assertThat(reloadedA.getSignName()).isEqualTo("V2");
        assertThat(loadCounts.get("tenant-a").get()).isEqualTo(2);

        // 3. TTL 过期后重新加载最新配置
        signVersion.set("V3");
        nowRef.set(nowRef.get().plus(Duration.ofMinutes(11)));
        SmsChannel expiredReloadedA = registry.get("tenant-a");
        assertThat(expiredReloadedA.getSignName()).isEqualTo("V3");
        assertThat(loadCounts.get("tenant-a").get()).isEqualTo(3);
    }

    @Test
    @DisplayName("SmsChannelEvictEvent 精准驱逐动态缓存并联动清理 LoadBalancer 与 CircuitBreaker 状态")
    void shouldEvictDynamicCacheAndResetLoadBalancerAndCircuitBreakerOnEvent() {
        SmsProperties props = new SmsProperties();
        AtomicReference<String> secretVersion = new AtomicReference<>("SK_V1");
        SmsChannelProvider provider = name -> "dyn-ch".equals(name)
                ? SmsChannel.builder()
                        .name("dyn-ch")
                        .supplier(SmsSupplier.MOCK)
                        .accessKeySecret(secretVersion.get())
                        .weight(5)
                        .defaultTemplateId("TPL_1")
                        .build()
                : null;

        SmsChannelRegistry registry = new SmsChannelRegistry(props, provider);
        SmsChannelCircuitBreaker cb = new SmsChannelCircuitBreaker(props.getGuard());
        SmsChannelLoadBalancer lb = new SmsChannelLoadBalancer(registry, cb);

        // 预热缓存、产生负载权重残留并触发熔断开路
        assertThat(registry.get("dyn-ch").getAccessKeySecret()).isEqualTo("SK_V1");
        registry.register(SmsChannel.builder().name("dyn-ch-other").supplier(SmsSupplier.MOCK).weight(1).build());
        lb.selectChannel(List.of("dyn-ch", "dyn-ch-other"));
        assertThat(lb.getCurrentWeight("dyn-ch")).isNotZero();
        for (int i = 0; i < 5; i++) {
            cb.recordFailure("dyn-ch");
        }
        assertThat(cb.isOpen("dyn-ch")).isTrue();

        // 触发精准 SmsChannelEvictEvent + Redis Pub/Sub 广播
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ApplicationEventPublisher localPublisher = mock(ApplicationEventPublisher.class);
        RedisSmsChannelEvictBroadcaster broadcaster = new RedisSmsChannelEvictBroadcaster(
                redisTemplate, localPublisher, "ateng:sms:channel:evict");
        SmsChannelEvictListener listener = new SmsChannelEvictListener(registry, lb, cb, broadcaster);

        secretVersion.set("SK_V2");
        listener.onChannelEvict(new SmsChannelEvictEvent(this, "dyn-ch"));

        assertThat(registry.dynamicSize()).isZero();
        assertThat(lb.getCurrentWeight("dyn-ch")).isZero();
        assertThat(cb.isOpen("dyn-ch")).isFalse();
        assertThat(registry.get("dyn-ch").getAccessKeySecret()).isEqualTo("SK_V2");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("ateng:sms:channel:evict"), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).contains("dyn-ch").contains(broadcaster.getNodeId());

        // 远端节点消息广播防回环：同 nodeId 忽略，异 nodeId 触发带 fromCluster=true 的本地事件
        broadcaster.onMessage(new DefaultMessage(
                "ateng:sms:channel:evict".getBytes(StandardCharsets.UTF_8),
                jsonCaptor.getValue().getBytes(StandardCharsets.UTF_8)
        ), null);
        org.mockito.Mockito.verifyNoInteractions(localPublisher);

        String remotePayload = "{\"nodeId\":\"remote-node-xyz\",\"channelName\":\"dyn-ch\"}";
        broadcaster.onMessage(new DefaultMessage(
                "ateng:sms:channel:evict".getBytes(StandardCharsets.UTF_8),
                remotePayload.getBytes(StandardCharsets.UTF_8)
        ), null);
        ArgumentCaptor<SmsChannelEvictEvent> evtCaptor = ArgumentCaptor.forClass(SmsChannelEvictEvent.class);
        verify(localPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().isFromCluster()).isTrue();
        assertThat(evtCaptor.getValue().getChannelName()).isEqualTo("dyn-ch");
    }

    @Test
    @DisplayName("register/unregister 与 SmsTemplate.evictChannelCache 联动清理缓存、权重与熔断器状态")
    void shouldCleanStaleStatesOnRegisterUnregisterAndTemplateEvict() {
        SmsProperties props = new SmsProperties();
        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        SmsChannelCircuitBreaker cb = new SmsChannelCircuitBreaker(props.getGuard());
        SmsChannelLoadBalancer lb = new SmsChannelLoadBalancer(registry, cb);
        SmsTemplate template = new SmsTemplate(
                props, List.of(new MockSmsProvider()), registry, null, null, null, null,
                cb, lb, null, null, null, null, null, null, null
        );

        registry.register(SmsChannel.builder().name("ch-a").supplier(SmsSupplier.MOCK).weight(3).build());
        registry.register(SmsChannel.builder().name("ch-b").supplier(SmsSupplier.MOCK).weight(1).build());
        lb.selectChannel();
        for (int i = 0; i < 5; i++) {
            cb.recordFailure("ch-a");
        }
        assertThat(lb.getCurrentWeight("ch-a")).isNotZero();
        assertThat(cb.isOpen("ch-a")).isTrue();

        // 更新注册 ch-a 自动联动清零旧权重与熔断态
        template.registerChannel(SmsChannel.builder().name("ch-a").supplier(SmsSupplier.MOCK).weight(2).build());
        assertThat(lb.getCurrentWeight("ch-a")).isZero();
        assertThat(cb.isOpen("ch-a")).isFalse();

        // 无事件发布器时调用 evictChannelCache / evictAllChannelCache 同样安全清理
        for (int i = 0; i < 5; i++) {
            cb.recordFailure("ch-b");
        }
        template.evictChannelCache("ch-b");
        assertThat(cb.isOpen("ch-b")).isFalse();

        template.evictAllChannelCache();
        assertThat(registry.dynamicSize()).isZero();
    }
}
