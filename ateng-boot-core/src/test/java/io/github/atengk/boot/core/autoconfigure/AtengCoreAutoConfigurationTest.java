package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.aspect.SlowAlertAspect;
import io.github.atengk.boot.core.aspect.SlowAlertEvent;
import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.id.SnowflakeIdInfo;
import io.github.atengk.boot.core.properties.CoreProperties;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.LambdaUtil;
import io.github.atengk.boot.core.util.SpelUtil;
import io.github.atengk.boot.core.util.SpringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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

    @AfterEach
    void tearDown() {
        IdUtil.resetSnowflake();
        SpelUtil.configureCache(1024, null);
        LambdaUtil.configureCache(1024, null);
        SpelUtil.clearCache();
        LambdaUtil.clearCache();
    }

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
            assertThat(IdUtil.getSnowflake()).isSameAs(snowflake);
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

                    // 验证 IdUtil 静态门面已自动与容器配置同步
                    assertThat(IdUtil.getSnowflake()).isSameAs(snowflake);
                    long id = IdUtil.nextId();
                    SnowflakeIdInfo info = IdUtil.parseSnowflake(id);
                    assertThat(info.workerId()).isEqualTo(12L);
                    assertThat(info.datacenterId()).isEqualTo(23L);
                    assertThat(info.epoch()).isEqualTo(1700000000000L);
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
                    assertThat(IdUtil.getSnowflake()).isSameAs(snowflake);
                    SnowflakeIdInfo info = IdUtil.parseSnowflake(IdUtil.nextId());
                    assertThat(info.workerId()).isEqualTo(9L);
                    assertThat(info.datacenterId()).isEqualTo(9L);
                });
    }

    @Test
    @DisplayName("上下文关闭解绑：容器关闭时自动恢复 IdUtil 默认发号器引用")
    void shouldResetIdUtilWhenContextCloses() {
        Snowflake defaultSnowflake = IdUtil.getSnowflake();
        contextRunner
                .withPropertyValues(
                        "ateng.boot.core.snowflake.worker-id=18",
                        "ateng.boot.core.snowflake.datacenter-id=28"
                )
                .run(context -> {
                    Snowflake bean = context.getBean(Snowflake.class);
                    assertThat(IdUtil.getSnowflake()).isSameAs(bean);
                });
        // ApplicationContextRunner.run 执行完毕已关闭上下文，DisposableBean 自动对称解绑
        assertThat(IdUtil.getSnowflake()).isSameAs(defaultSnowflake);
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSnowflakeConfiguration {
        @Bean
        public Snowflake customSnowflake() {
            return new Snowflake(9L, 9L);
        }
    }

    @Test
    @DisplayName("缓存配置绑定：启动时自动读取 CoreProperties.cache 并绑定至 SpelUtil 与 LambdaUtil")
    void shouldBindCachePropertiesToSpelAndLambdaUtils() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.core.cache.default-max-capacity=256",
                        "ateng.boot.core.cache.default-ttl=15m"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CoreProperties.class);
                    CoreProperties properties = context.getBean(CoreProperties.class);
                    assertThat(properties.getCache().getDefaultMaxCapacity()).isEqualTo(256);
                    assertThat(properties.getEffectiveCacheTtl().toMinutes()).isEqualTo(15L);

                    // 验证 SpelUtil 与 LambdaUtil 在绑定配置后正常工作
                    SpelUtil.parseExpression("100 + 200");
                    assertThat(SpelUtil.getCacheSize()).isEqualTo(1);

                    assertThat(LambdaUtil.getFieldName(SampleTestBean::getName)).isEqualTo("name");
                    assertThat(LambdaUtil.getCacheSize()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("日志配置绑定与引导注册验证：绑定 clean-history-on-start，EnvironmentPostProcessor 唯独注册于 spring.factories 且禁用 EnableAutoConfiguration")
    void shouldBindLoggingCleanHistoryOnStartAndRegisterEnvironmentPostProcessorsInSpringFactories() throws Exception {
        contextRunner
                .withPropertyValues("ateng.boot.core.logging.file.clean-history-on-start=true")
                .run(context -> {
                    CoreProperties properties = context.getBean(CoreProperties.class);
                    assertThat(properties.getLogging().getFile().isCleanHistoryOnStart()).isTrue();
                });

        boolean foundCoreEnvironmentPostProcessors = false;
        java.util.Enumeration<java.net.URL> urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            java.net.URL url = urls.nextElement();
            try (java.io.InputStream is = url.openStream()) {
                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (content.contains("io.github.atengk.boot.core.env.SystemEnvironmentPostProcessor")) {
                    foundCoreEnvironmentPostProcessors = true;
                    assertThat(content).contains("org.springframework.boot.env.EnvironmentPostProcessor");
                    assertThat(content).contains("io.github.atengk.boot.core.env.LoggingEnvironmentPostProcessor");
                    assertThat(content).doesNotContain("EnableAutoConfiguration");
                }
            }
        }
        assertThat(foundCoreEnvironmentPostProcessors)
                .as("Spring Boot 3.3 EnvironmentPostProcessor 必须在 META-INF/spring.factories 注册")
                .isTrue();

        java.util.Enumeration<java.net.URL> legacyImports = getClass().getClassLoader()
                .getResources("META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports");
        while (legacyImports.hasMoreElements()) {
            java.net.URL url = legacyImports.nextElement();
            try (java.io.InputStream is = url.openStream()) {
                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(content).doesNotContain("io.github.atengk.boot.core.env.SystemEnvironmentPostProcessor");
                assertThat(content).doesNotContain("io.github.atengk.boot.core.env.LoggingEnvironmentPostProcessor");
            }
        }
    }

    @Test
    @DisplayName("@SlowAlert 切面自动装配时自动注入 ApplicationEventPublisher 并发布 SlowAlertEvent")
    void shouldAutoConfigureSlowAlertAspectWithEventPublisher() {
        List<SlowAlertEvent> captured = new ArrayList<>();
        contextRunner
                .withBean(SlowAlertSampleTarget.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SlowAlertAspect.class);
                    org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                            new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(context.getBean(SlowAlertSampleTarget.class));
                    factory.addAspect(context.getBean(SlowAlertAspect.class));
                    SlowAlertSampleTarget proxy = factory.getProxy();

                    context.addApplicationListener(event -> {
                        if (event instanceof SlowAlertEvent slowAlertEvent) {
                            captured.add(slowAlertEvent);
                        }
                    });

                    String res = proxy.runSlow("auto-task", "topSecret999");
                    assertThat(res).isEqualTo("done:auto-task");
                    assertThat(captured).hasSize(1);
                    SlowAlertEvent event = captured.get(0);
                    assertThat(event.getClassName()).isEqualTo(SlowAlertSampleTarget.class.getName());
                    assertThat(event.getMethodName()).isEqualTo("runSlow");
                    assertThat(event.getElapsedMs()).isGreaterThanOrEqualTo(20L);
                    assertThat(event.getThresholdMs()).isEqualTo(20L);
                    assertThat(event.getArgsSummary()).contains("******").doesNotContain("topSecret999");
                });
    }

    static class SlowAlertSampleTarget {
        @io.github.atengk.boot.core.annotation.SlowAlert(threshold = 20, includeArgs = true)
        public String runSlow(String taskId, String password) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "done:" + taskId;
        }
    }

    static class SampleTestBean {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
