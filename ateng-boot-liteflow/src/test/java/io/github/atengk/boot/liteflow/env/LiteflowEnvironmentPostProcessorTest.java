package io.github.atengk.boot.liteflow.env;

import io.github.atengk.boot.liteflow.thread.LiteflowGlobalExecutorBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiteflowEnvironmentPostProcessor 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@DisplayName("LiteflowEnvironmentPostProcessor 环境后置处理器单元测试")
class LiteflowEnvironmentPostProcessorTest {

    private final LiteflowEnvironmentPostProcessor processor = new LiteflowEnvironmentPostProcessor();

    @Test
    @DisplayName("验证默认情况下成功注入 LiteFlow 受管线程池与安全默认属性")
    void testPostProcessEnvironmentDefaults() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(LiteflowEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("liteflow.global-thread-pool-executor-class"))
                .isEqualTo(LiteflowGlobalExecutorBuilder.class.getName());
        assertThat(environment.getProperty("liteflow.enable-virtual-thread", Boolean.class)).isFalse();
        assertThat(environment.getProperty("liteflow.support-multiple-type", Boolean.class)).isTrue();
        assertThat(environment.getProperty("liteflow.rule-source")).isEqualTo("classpath*:liteflow/*.el.xml");
    }

    @Test
    @DisplayName("验证当 ateng.boot.liteflow.enabled 为 false 时不注入任何默认属性")
    void testPostProcessEnvironmentDisabled() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("custom",
                Collections.singletonMap("ateng.boot.liteflow.enabled", false)));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(LiteflowEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("验证环境后处理器具有合理的执行排序优先级")
    void testOrder() {
        assertThat(processor.getOrder()).isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }
}
