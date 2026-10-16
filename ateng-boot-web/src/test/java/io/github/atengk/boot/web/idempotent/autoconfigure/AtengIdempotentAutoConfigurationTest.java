package io.github.atengk.boot.web.idempotent.autoconfigure;

import io.github.atengk.boot.web.idempotent.aspect.IdempotentAspect;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedisIdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedissonIdempotentKeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口防重幂等自动装配单元测试
 * 验证 Tier 1(Redisson) -> Tier 2(Redis) -> Tier 3(InMemory) 三级抢占与退让机制
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("接口防重幂等自动装配测试")
class AtengIdempotentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengIdempotentAutoConfiguration.class));

    @Test
    @DisplayName("默认无分布式中间件时成功自动装配 Tier 3 单机内存存储与切面")
    void shouldAutoConfigureInMemoryStoreAndAspectByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(IdempotentKeyStore.class);
            assertThat(context).hasSingleBean(InMemoryIdempotentKeyStore.class);
            assertThat(context).doesNotHaveBean(RedissonIdempotentKeyStore.class);
            assertThat(context).doesNotHaveBean(RedisIdempotentKeyStore.class);
            assertThat(context).hasSingleBean(IdempotentAspect.class);
        });
    }

    @Test
    @DisplayName("仅存在 StringRedisTemplate 时命中装配 Tier 2 Redis 存储")
    void shouldAutoConfigureRedisStoreWhenStringRedisTemplatePresent() {
        contextRunner.withUserConfiguration(RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(RedisIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(RedissonIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    @DisplayName("仅存在 RedissonClient 时命中装配 Tier 1 Redisson 存储")
    void shouldAutoConfigureRedissonStoreWhenRedissonClientPresent() {
        contextRunner.withUserConfiguration(RedissonMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(RedissonIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(RedisIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    @DisplayName("同时存在 RedissonClient 与 StringRedisTemplate 时由 Tier 1 Redisson 抢占胜出")
    void shouldPrioritizeRedissonStoreOverRedisStoreWhenBothPresent() {
        contextRunner.withUserConfiguration(RedissonMockConfig.class, RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(RedissonIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(RedisIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    @DisplayName("用户显式自定义 IdempotentKeyStore 时三级自动装配全量退让")
    void shouldRespectCustomKeyStoreBean() {
        contextRunner.withUserConfiguration(CustomKeyStoreConfig.class, RedissonMockConfig.class, RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentKeyStore.class);
                    assertThat(context.getBean(IdempotentKeyStore.class)).isInstanceOf(CustomKeyStore.class);
                    assertThat(context).doesNotHaveBean(RedissonIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(RedisIdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotentKeyStore.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    @DisplayName("配置 enabled=false 时完全关闭装配")
    void shouldBackOffWhenDisabled() {
        contextRunner.withPropertyValues("ateng.boot.web.idempotent.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(IdempotentAspect.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RedissonMockConfig {
        @Bean
        public RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisMockConfig {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomKeyStoreConfig {
        @Bean
        public IdempotentKeyStore customIdempotentKeyStore() {
            return new CustomKeyStore();
        }
    }

    static class CustomKeyStore implements IdempotentKeyStore {
        @Override
        public boolean tryLock(String key, long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void unlock(String key) {
        }
    }
}
