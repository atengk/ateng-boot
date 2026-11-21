package io.github.atengk.boot.liteflow.env;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.property.LiteflowConfig;
import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import io.github.atengk.boot.liteflow.autoconfigure.AtengLiteflowAutoConfiguration;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.thread.LiteflowGlobalExecutorBuilder;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.env.EnvironmentPostProcessorsFactory;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> processor.postProcessEnvironment(context.getEnvironment(), null))
            .withConfiguration(AutoConfigurations.of(
                    LiteflowPropertyAutoConfiguration.class,
                    LiteflowMainAutoConfiguration.class,
                    AtengLiteflowAutoConfiguration.class
            ));

    @Test
    @DisplayName("验证默认情况下成功注入受管线程池与安全默认属性，且不硬编码覆盖 rule-source")
    void testPostProcessEnvironmentDefaults() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(LiteflowEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("liteflow.global-thread-pool-executor-class"))
                .isEqualTo(LiteflowGlobalExecutorBuilder.class.getName());
        assertThat(environment.getProperty("liteflow.enable-virtual-thread", Boolean.class)).isFalse();
        assertThat(environment.getProperty("liteflow.support-multiple-type", Boolean.class)).isTrue();
        assertThat(environment.getProperty("liteflow.rule-source")).isNull();
    }

    @Test
    @DisplayName("验证默认与自定义 ateng.boot.liteflow.rule-source 正确桥接，且显式 liteflow.rule-source 保持最高优先级")
    void testRuleSourceBridgePriority() {
        // 1. 均未配置时，桥接 AtengLiteflowProperties 默认值（同时包含 .el.xml 与 .el.yml）
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LiteflowConfig.class);
            LiteflowConfig config = context.getBean(LiteflowConfig.class);
            assertThat(config.getRuleSource()).isEqualTo("classpath*:liteflow/*.el.xml,classpath*:liteflow/*.el.yml");
        });

        // 2. 仅配置 ateng.boot.liteflow.rule-source 时，桥接至 LiteflowConfig
        contextRunner
                .withPropertyValues("ateng.boot.liteflow.rule-source=classpath*:custom/*.el.yml")
                .run(context -> {
                    LiteflowConfig config = context.getBean(LiteflowConfig.class);
                    assertThat(config.getRuleSource()).isEqualTo("classpath*:custom/*.el.yml");
                });

        // 3. 同时配置显式 liteflow.rule-source 与 ateng.boot.liteflow.rule-source 时，显式原生配置优先
        contextRunner
                .withPropertyValues(
                        "liteflow.rule-source=classpath:liteflow/template-flow.el.xml",
                        "ateng.boot.liteflow.rule-source=classpath*:custom/*.el.yml"
                )
                .run(context -> {
                    LiteflowConfig config = context.getBean(LiteflowConfig.class);
                    assertThat(config.getRuleSource()).isEqualTo("classpath:liteflow/template-flow.el.xml");
                });
    }

    @Test
    @DisplayName("严格遵循 Spring Boot 3.3 装配规范：@AutoConfiguration 唯一注册于 imports，EnvironmentPostProcessor 注册于 spring.factories")
    void testSpringBoot3RegistrationContract() throws Exception {
        Path moduleClassesRoot = Path.of(AtengLiteflowAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path springFactoriesPath = moduleClassesRoot.resolve("META-INF/spring.factories");
        assertThat(Files.exists(springFactoriesPath)).isTrue();

        String factoriesContent = Files.readString(springFactoriesPath, StandardCharsets.UTF_8);
        assertThat(factoriesContent).doesNotContain("EnableAutoConfiguration");
        assertThat(factoriesContent).contains(LiteflowEnvironmentPostProcessor.class.getName());

        EnvironmentPostProcessorsFactory factory = EnvironmentPostProcessorsFactory.fromSpringFactories(getClass().getClassLoader());
        assertThat(factory.getEnvironmentPostProcessors(new DeferredLogs(), new DefaultBootstrapContext()))
                .anyMatch(LiteflowEnvironmentPostProcessor.class::isInstance);

        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
        assertThat(candidates.getCandidates()).contains(
                AtengLiteflowAutoConfiguration.class.getName(),
                "io.github.atengk.boot.liteflow.autoconfigure.LiteflowMetricsAutoConfiguration"
        );
    }

    @Test
    @DisplayName("验证仅配置 ateng.boot.liteflow.rule-source 时可真实加载并执行 .el.yml 规则文件")
    void testAtengRuleSourceLoadsRealYmlRuleFile(@TempDir Path tempDir) throws Exception {
        Path ruleFile = tempDir.resolve("bridge-test.el.yml");
        Files.writeString(ruleFile, """
                flow:
                  chain:
                    - name: ymlBridgeChain
                      value: "THEN(ymlBridgeStep);"
                """, StandardCharsets.UTF_8);

        String filePattern = ruleFile.toAbsolutePath().toString();
        contextRunner
                .withBean("ymlBridgeStep", YmlBridgeStep.class, YmlBridgeStep::new)
                .withPropertyValues(
                        "liteflow.enable=true",
                        "liteflow.parse-mode=PARSE_ALL_ON_START",
                        "ateng.boot.liteflow.rule-source=" + filePattern
                )
                .run(context -> {
                    try {
                        assertThat(context).hasSingleBean(LiteflowTemplate.class);
                        LiteflowTemplate template = context.getBean(LiteflowTemplate.class);
                        assertThat(template.containChain("ymlBridgeChain")).isTrue();
                        YmlBridgeContext result = template.execute("ymlBridgeChain", YmlBridgeContext.class);
                        assertThat(result.isExecuted()).isTrue();
                    } finally {
                        FlowBus.removeChain("ymlBridgeChain");
                    }
                });
    }

    @Data
    static class YmlBridgeContext {
        private boolean executed;
    }

    @Component("ymlBridgeStep")
    static class YmlBridgeStep extends NodeComponent {
        @Override
        public void process() {
            YmlBridgeContext ctx = this.getContextBean(YmlBridgeContext.class);
            if (ctx != null) {
                ctx.setExecuted(true);
            }
        }
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
