package io.github.atengk.boot.statemachine.autoconfigure;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.core.DefaultStateMachine;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import io.github.atengk.boot.statemachine.interceptor.LoggingStateMachineInterceptor;
import io.github.atengk.boot.statemachine.interceptor.MetricsStateMachineInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机自动装配与双通道获取单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class AtengStateMachineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengStateMachineAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        StateMachineFactory.clear();
    }

    enum TestState {
        S1, S2
    }

    enum TestEvent {
        E1
    }

    @Configuration
    static class TestStateMachineConfiguration {

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        public StateMachine<TestState, TestEvent, Object> testOrderStateMachine() {
            StateMachineBuilder<TestState, TestEvent, Object> builder = StateMachineBuilderFactory.create();
            builder.externalTransition().from(TestState.S1).to(TestState.S2).on(TestEvent.E1);
            return builder.build("SPRING_MANAGED_MACHINE");
        }
    }

    @Test
    @DisplayName("测试自动装配默认启用并装配核心拦截器与初始化器")
    void testDefaultAutoConfiguration() {
        contextRunner
                .withUserConfiguration(TestStateMachineConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengStateMachineAutoConfiguration.class);
                    assertThat(context).hasSingleBean(LoggingStateMachineInterceptor.class);
                    assertThat(context).hasSingleBean(MetricsStateMachineInterceptor.class);
                    assertThat(context).hasSingleBean(StateMachineInitializer.class);

                    // 验证双通道获取 1: Spring 容器泛型获取
                    StateMachine<?, ?, ?> springBean = context.getBean("testOrderStateMachine", StateMachine.class);
                    assertThat(springBean).isNotNull();
                    assertThat(springBean.getMachineId()).isEqualTo("SPRING_MANAGED_MACHINE");

                    // 验证拦截器已被自动注入至 DefaultStateMachine
                    assertThat(springBean).isInstanceOf(DefaultStateMachine.class);
                    DefaultStateMachine<?, ?, ?> defaultStateMachine = (DefaultStateMachine<?, ?, ?>) springBean;
                    assertThat(defaultStateMachine.getInterceptors()).isNotEmpty();

                    // 验证双通道获取 2: 全局工厂获取
                    StateMachine<TestState, TestEvent, Object> factoryRetrieved =
                            StateMachineFactory.get("SPRING_MANAGED_MACHINE");
                    assertThat(factoryRetrieved).isSameAs(springBean);
                });
    }

    @Test
    @DisplayName("测试 ateng.boot.statemachine.enabled=false 时自动配置完全后退")
    void testDisabledAutoConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.statemachine.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengStateMachineAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(LoggingStateMachineInterceptor.class);
                    assertThat(context).doesNotHaveBean(StateMachineInitializer.class);
                });
    }

    @Test
    @DisplayName("测试 logging.enabled=false 时不装配日志拦截器")
    void testLoggingDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.statemachine.logging.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengStateMachineAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(LoggingStateMachineInterceptor.class);
                });
    }

    @Test
    @DisplayName("测试 metrics.enabled=false 时不装配度量拦截器")
    void testMetricsDisabled() {
        contextRunner
                .withUserConfiguration(TestStateMachineConfiguration.class)
                .withPropertyValues("ateng.boot.statemachine.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MetricsStateMachineInterceptor.class);
                });
    }

    @Configuration
    static class DuplicateMachineIdConfiguration {
        @Bean
        public StateMachine<TestState, TestEvent, Object> machineA() {
            StateMachineBuilder<TestState, TestEvent, Object> builder = StateMachineBuilderFactory.create();
            builder.externalTransition().from(TestState.S1).to(TestState.S2).on(TestEvent.E1);
            return builder.build("CONFLICT_ID");
        }

        @Bean
        public StateMachine<TestState, TestEvent, Object> machineB() {
            StateMachineBuilder<TestState, TestEvent, Object> builder = StateMachineBuilderFactory.create();
            builder.externalTransition().from(TestState.S1).to(TestState.S2).on(TestEvent.E1);
            return builder.build("CONFLICT_ID");
        }
    }

    @Test
    @DisplayName("测试容器中定义重复 machineId 的不同 Bean 时触发 DUPLICATE_MACHINE_ID 启动失败")
    void testDuplicateMachineIdConflict() {
        contextRunner
                .withUserConfiguration(DuplicateMachineIdConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(io.github.atengk.boot.statemachine.exception.StateMachineException.class)
                            .hasMessageContaining("重复注册冲突");
                });
    }
}
