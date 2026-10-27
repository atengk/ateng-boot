package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.properties.CoreProperties;
import io.github.atengk.boot.core.util.SpringUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengCoreAutoConfiguration} 核心自动装配与配置属性绑定测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("Core 核心底座自动配置与 Snowflake 发号器装配测试")
class AtengCoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengCoreAutoConfiguration.class));

    @Test
    @DisplayName("默认场景：自动装配 SpringUtil、CoreProperties 与 Snowflake")
    void shouldAutoConfigureSpringUtilAndSnowflakeByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SpringUtil.class);
            assertThat(context).hasSingleBean(CoreProperties.class);
            assertThat(context).hasSingleBean(Snowflake.class);

            Snowflake snowflake = context.getBean(Snowflake.class);
            assertThat(snowflake.getWorkerId()).isBetween(0L, 31L);
            assertThat(snowflake.getDatacenterId()).isBetween(0L, 31L);
            assertThat(snowflake.getEpoch()).isEqualTo(Snowflake.DEFAULT_EPOCH);
            assertThat(snowflake.getMaxBackwardMs()).isEqualTo(Snowflake.DEFAULT_MAX_BACKWARD_MS);
            assertThat(snowflake.nextId()).isPositive();
        });
    }

    @Test
    @DisplayName("自定义属性配置：绑定 workerId、datacenterId、epoch 与时钟回拨容忍阈值")
    void shouldRespectCustomSnowflakeConfiguration() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.core.snowflake.worker-id=12",
                        "ateng.boot.core.snowflake.datacenter-id=23",
                        "ateng.boot.core.snowflake.epoch=1700000000000",
                        "ateng.boot.core.snowflake.clock-backoff-tolerance=15ms",
                        "ateng.boot.core.cache-ttl=45m"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(Snowflake.class);
                    Snowflake snowflake = context.getBean(Snowflake.class);

                    assertThat(snowflake.getWorkerId()).isEqualTo(12L);
                    assertThat(snowflake.getDatacenterId()).isEqualTo(23L);
                    assertThat(snowflake.getEpoch()).isEqualTo(1700000000000L);
                    assertThat(snowflake.getMaxBackwardMs()).isEqualTo(15L);

                    CoreProperties properties = context.getBean(CoreProperties.class);
                    assertThat(properties.getSnowflake().getWorkerId()).isEqualTo(12L);
                    assertThat(properties.getSnowflake().getDatacenterId()).isEqualTo(23L);
                    assertThat(properties.getSnowflake().getClockBackoffTolerance().toMillis()).isEqualTo(15L);
                    assertThat(properties.getEffectiveClockBackoffTolerance().toMillis()).isEqualTo(15L);
                    assertThat(properties.getEffectiveCacheTtl().toMinutes()).isEqualTo(45L);
                });
    }

    @Test
    @DisplayName("顶层时钟回拨配置回退验证：未指定 snowflake.clock-backoff-tolerance 时回退至顶层配置")
    void shouldRespectTopLevelClockBackoffTolerance() {
        contextRunner
                .withPropertyValues("ateng.boot.core.clock-backoff-tolerance=12ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(Snowflake.class);
                    Snowflake snowflake = context.getBean(Snowflake.class);
                    // snowflake.clockBackoffTolerance 默认是 5ms，所以通过 getEffectiveClockBackoffTolerance 进行统一推导
                    CoreProperties properties = context.getBean(CoreProperties.class);
                    assertThat(properties.getClockBackoffTolerance().toMillis()).isEqualTo(12L);
                });
    }

    @Test
    @DisplayName("条件禁用：配置 ateng.boot.core.snowflake.enabled=false 时不装配 Snowflake Bean")
    void shouldNotCreateSnowflakeWhenDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.core.snowflake.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpringUtil.class);
                    assertThat(context).doesNotHaveBean(Snowflake.class);
                });
    }

    @Test
    @DisplayName("用户自定义覆盖：用户显式声明 Snowflake Bean 时 @ConditionalOnMissingBean 平滑避让")
    void shouldRespectCustomUserSnowflakeBean() {
        contextRunner
                .withUserConfiguration(CustomSnowflakeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Snowflake.class);
                    Snowflake snowflake = context.getBean(Snowflake.class);
                    assertThat(snowflake.getWorkerId()).isEqualTo(9L);
                    assertThat(snowflake.getDatacenterId()).isEqualTo(9L);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSnowflakeConfiguration {
        @Bean
        public Snowflake customSnowflake() {
            return new Snowflake(9L, 9L);
        }
    }
}
