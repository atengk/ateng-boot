package io.github.atengk.boot.web.idempotent.autoconfigure;

import io.github.atengk.boot.web.idempotent.aspect.IdempotentAspect;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口防重幂等自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("接口防重幂等自动装配测试")
class AtengIdempotentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengIdempotentAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功自动装配内存存储与切面")
    void shouldAutoConfigureInMemoryStoreAndAspectByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(IdempotentKeyStore.class);
            assertThat(context).hasSingleBean(InMemoryIdempotentKeyStore.class);
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

    @Test
    @DisplayName("用户自定义 IdempotentKeyStore 时默认内存实现自动退出")
    void shouldRespectCustomKeyStoreBean() {
        contextRunner.withUserConfiguration(CustomKeyStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotentKeyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotentKeyStore.class);
                    assertThat(context.getBean(IdempotentKeyStore.class)).isInstanceOf(CustomKeyStore.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
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
