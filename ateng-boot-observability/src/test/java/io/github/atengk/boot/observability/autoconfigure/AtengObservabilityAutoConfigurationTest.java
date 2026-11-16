package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.env.ObservabilityEnvironmentPostProcessor;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.env.EnvironmentPostProcessorsFactory;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengObservabilityAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengObservabilityAutoConfiguration 自动装配单元测试")
class AtengObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengObservabilityAutoConfiguration.class));

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("非 Web 独立环境下默认自动装配成功并注入 ObservabilityProperties")
    void testDefaultAutoConfigurationInNonWebEnvironment() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengObservabilityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ObservabilityProperties.class);

            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getHealth().getTimeout().toSeconds()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("Web 环境下默认自动装配成功并加载配置属性")
    void testDefaultAutoConfigurationInWebEnvironment() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengObservabilityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ObservabilityProperties.class);

            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getMetrics().getPrometheus().isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.enabled=false 时自动装配平滑跳过")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengObservabilityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ObservabilityProperties.class);
                });
    }

    @Test
    @DisplayName("严格遵循 Spring Boot 3.3.x 装配规范：@AutoConfiguration 唯一归属 *.imports，spring.factories 仅注册 EnvironmentPostProcessor")
    void testSpringBoot3AutoConfigurationImportsAndEnvironmentPostProcessorFactories() throws IOException, URISyntaxException {
        Path moduleClassesRoot = Path.of(AtengObservabilityAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path springFactoriesPath = moduleClassesRoot.resolve("META-INF/spring.factories");
        assertThat(Files.exists(springFactoriesPath)).isTrue();

        String factoriesContent = Files.readString(springFactoriesPath, StandardCharsets.UTF_8);
        assertThat(factoriesContent).doesNotContain("EnableAutoConfiguration");
        assertThat(factoriesContent).contains(ObservabilityEnvironmentPostProcessor.class.getName());

        EnvironmentPostProcessorsFactory factory = EnvironmentPostProcessorsFactory.fromSpringFactories(getClass().getClassLoader());
        assertThat(factory.getEnvironmentPostProcessors(new DeferredLogs(), new DefaultBootstrapContext()))
                .anyMatch(ObservabilityEnvironmentPostProcessor.class::isInstance);

        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
        assertThat(candidates.getCandidates()).contains(
                AtengObservabilityAutoConfiguration.class.getName(),
                AtengMetricsAutoConfiguration.class.getName(),
                AtengHealthAutoConfiguration.class.getName(),
                AtengSecurityAutoConfiguration.class.getName(),
                AtengAdminClientAutoConfiguration.class.getName()
        );
    }
}
