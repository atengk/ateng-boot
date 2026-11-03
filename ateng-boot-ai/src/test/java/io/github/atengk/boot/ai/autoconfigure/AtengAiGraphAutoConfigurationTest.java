package io.github.atengk.boot.ai.autoconfigure;

import io.github.atengk.boot.ai.checkpoint.GraphCheckpointSaver;
import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.checkpoint.RedisCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认知状态图自动装配单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AtengAiGraphAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiGraphAutoConfiguration.class));

    @Configuration
    static class RedisMockConfiguration {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }

    @Configuration
    static class CustomSaverConfiguration {
        @Bean
        public GraphCheckpointSaver customGraphCheckpointSaver() {
            return new MemoryCheckpointSaver();
        }
    }

    @Test
    @DisplayName("默认 AUTO 模式且无 Redis 时装配 MemoryCheckpointSaver")
    void testAutoWithoutRedis() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GraphCheckpointSaver.class);
            assertThat(context.getBean(GraphCheckpointSaver.class)).isInstanceOf(MemoryCheckpointSaver.class);
        });
    }

    @Test
    @DisplayName("默认 AUTO 模式存在 Redis 时装配 RedisCheckpointSaver")
    void testAutoWithRedis() {
        contextRunner.withUserConfiguration(RedisMockConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GraphCheckpointSaver.class);
                    assertThat(context.getBean(GraphCheckpointSaver.class)).isInstanceOf(RedisCheckpointSaver.class);
                });
    }

    @Test
    @DisplayName("显式配置 MEMORY 模式时装配 MemoryCheckpointSaver")
    void testExplicitMemory() {
        contextRunner.withPropertyValues("ateng.boot.ai.graph.checkpoint.type=MEMORY")
                .withUserConfiguration(RedisMockConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GraphCheckpointSaver.class);
                    assertThat(context.getBean(GraphCheckpointSaver.class)).isInstanceOf(MemoryCheckpointSaver.class);
                });
    }

    @Test
    @DisplayName("显式配置 REDIS 模式且无 Redis 时启动失败抛出 AiException")
    void testExplicitRedisWithoutTemplateFails() {
        contextRunner.withPropertyValues("ateng.boot.ai.graph.checkpoint.type=REDIS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(AiException.class)
                            .hasMessageContaining("StringRedisTemplate");
                });
    }

    @Test
    @DisplayName("开关禁用测试：ateng.boot.ai.graph.enabled=false 时不装配任何 Graph Bean")
    void testDisabled() {
        contextRunner.withPropertyValues("ateng.boot.ai.graph.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GraphCheckpointSaver.class);
                });
    }

    @Test
    @DisplayName("Bean 缺省守卫：下游声明自定义 GraphCheckpointSaver 时平滑接管")
    void testCustomBeanTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomSaverConfiguration.class, RedisMockConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GraphCheckpointSaver.class);
                    assertThat(context.getBean(GraphCheckpointSaver.class))
                            .isSameAs(context.getBean(CustomSaverConfiguration.class).customGraphCheckpointSaver());
                });
    }
}
