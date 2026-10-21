package io.github.atengk.boot.realtime.autoconfigure;

import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RedissonRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.StandaloneRealtimeClusterBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实时通信底座主自动装配测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("实时底座自动装配测试")
class AtengRealtimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRealtimeAutoConfiguration.class));

    @Test
    @DisplayName("默认环境（无 RedissonClient）自动装配单机保底广播器")
    void testStandaloneFallbackByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RealtimeClusterBroadcaster.class);
            RealtimeClusterBroadcaster broadcaster = context.getBean(RealtimeClusterBroadcaster.class);
            assertThat(broadcaster).isInstanceOf(StandaloneRealtimeClusterBroadcaster.class);
            assertThat(broadcaster.isClusterEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("存在 RedissonClient 且默认 AUTO 模式自动升级为 RedissonRealtimeClusterBroadcaster")
    void testRedissonClusterAutoUpgrade() {
        runner.withUserConfiguration(MockRedissonConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RealtimeClusterBroadcaster.class);
                    RealtimeClusterBroadcaster broadcaster = context.getBean(RealtimeClusterBroadcaster.class);
                    assertThat(broadcaster).isInstanceOf(RedissonRealtimeClusterBroadcaster.class);
                    assertThat(broadcaster.isClusterEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("存在 RedissonClient 但配置为 STANDALONE 模式时强制使用单机广播器")
    void testRedissonStandaloneForced() {
        runner.withUserConfiguration(MockRedissonConfiguration.class)
                .withPropertyValues("ateng.boot.realtime.cluster-mode=standalone")
                .run(context -> {
                    assertThat(context).hasSingleBean(RealtimeClusterBroadcaster.class);
                    RealtimeClusterBroadcaster broadcaster = context.getBean(RealtimeClusterBroadcaster.class);
                    assertThat(broadcaster).isInstanceOf(StandaloneRealtimeClusterBroadcaster.class);
                    assertThat(broadcaster.isClusterEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedissonConfiguration {
        @Bean
        public RedissonClient mockRedissonClient() {
            RedissonClient client = mock(RedissonClient.class);
            RTopic topic = mock(RTopic.class);
            when(client.getTopic(anyString())).thenReturn(topic);
            return client;
        }
    }
}
