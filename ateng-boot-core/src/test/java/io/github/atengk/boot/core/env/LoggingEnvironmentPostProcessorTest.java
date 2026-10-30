package io.github.atengk.boot.core.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零 XML 日志引擎环境后处理器 LoggingEnvironmentPostProcessor 单测
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("零 XML 日志引擎环境后处理器单测")
class LoggingEnvironmentPostProcessorTest {

    private final LoggingEnvironmentPostProcessor processor = new LoggingEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();

    @Test
    @DisplayName("默认情况下自动注入带 traceId 与彩色的控制台及文件格式基线")
    void shouldInjectConsoleAndFilePatternsByDefault() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(LoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("logging.pattern.console"))
                .isEqualTo(LoggingEnvironmentPostProcessor.DEFAULT_CONSOLE_PATTERN);
        assertThat(environment.getProperty("logging.pattern.file"))
                .isEqualTo(LoggingEnvironmentPostProcessor.DEFAULT_FILE_PATTERN);
        assertThat(environment.getProperty("logging.pattern.dateformat"))
                .isEqualTo(LoggingEnvironmentPostProcessor.DEFAULT_DATE_FORMAT);
    }

    @Test
    @DisplayName("当日志大总开关 enabled 为 false 时，完全熔断退出")
    void shouldBypassWhenLoggingDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.logging.enabled", "false");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(LoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("当用户显式指定了 logging.config 时，框架 100% 平滑退让")
    void shouldBypassWhenCustomLoggingConfigPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.config", "classpath:custom-logback.xml");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(LoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("当用户在配置中显式自定义了 logging.pattern.console 时，框架 100% 平滑退让")
    void shouldBypassWhenCustomConsolePatternPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.pattern.console", "%msg%n");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(LoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("当 Classpath 中存在自定义 logback-spring.xml 或 logback.xml 时，框架 100% 平滑退让")
    void shouldBypassWhenCustomLogbackXmlInClasspath() {
        ClassLoader mockClassLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public java.net.URL getResource(String name) {
                if ("logback-spring.xml".equals(name) || "logback.xml".equals(name)) {
                    return getClass().getClassLoader().getResource("io/github/atengk/boot/core/logging/ateng-logback-base.xml");
                }
                return super.getResource(name);
            }
        };

        ConfigurableEnvironment environment = new StandardEnvironment();
        boolean hasCustom = LoggingEnvironmentPostProcessor.hasCustomLoggingConfiguration(environment, mockClassLoader);
        assertThat(hasCustom).isTrue();

        SpringApplication appWithMockCl = new SpringApplication();
        appWithMockCl.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader(mockClassLoader));
        processor.postProcessEnvironment(environment, appWithMockCl);

        assertThat(environment.getPropertySources().contains(LoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(environment.getProperty("logging.pattern.console")).isNull();
    }

    @Test
    @DisplayName("当开启 file.enabled 时，自动挂载内置双文件持久化 Logback 模板")
    void shouldSetFileLoggingConfigWhenFileEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.logging.file.enabled", "true");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("logging.config"))
                .isEqualTo(LoggingEnvironmentPostProcessor.CLASSPATH_LOGBACK_FILE);
    }

    @Test
    @DisplayName("当开启 file.enabled 且 split-error 为 false 时，挂载单文件持久化 Logback 模板")
    void shouldSetSingleFileLoggingConfigWhenSplitErrorFalse() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.logging.file.enabled", "true");
        environment.setProperty("ateng.boot.core.logging.file.split-error", "false");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("logging.config"))
                .isEqualTo(LoggingEnvironmentPostProcessor.CLASSPATH_LOGBACK_FILE_SINGLE);
    }

    @Test
    @DisplayName("双文件持久化模板落盘实测：INFO 与 ERROR 正常分流且包含 traceId 占位")
    void shouldGenerateSplitLogFilesAndRollUnderTempDir(@TempDir Path tempDir) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "test-demo");
        environment.setProperty("ateng.boot.core.logging.file.enabled", "true");
        environment.setProperty("ateng.boot.core.logging.file.path", tempDir.toAbsolutePath().toString().replace('\\', '/'));

        processor.postProcessEnvironment(environment, application);

        LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        org.springframework.boot.logging.LoggingInitializationContext context =
                new org.springframework.boot.logging.LoggingInitializationContext(environment);
        loggingSystem.initialize(context, environment.getProperty("logging.config"), null);

        Logger logger = LoggerFactory.getLogger("io.github.atengk.boot.test.LogDemo");
        logger.info("Demo info message for test");
        logger.error("Demo error message for test", new RuntimeException("Demo simulated error"));

        Path infoLogPath = tempDir.resolve("test-demo-info.log");
        Path errorLogPath = tempDir.resolve("test-demo-error.log");

        assertThat(Files.exists(infoLogPath)).isTrue();
        assertThat(Files.exists(errorLogPath)).isTrue();

        String infoContent = Files.readString(infoLogPath);
        String errorContent = Files.readString(errorLogPath);

        // 关闭 LoggerContext 释放 Windows 文件句柄，确保 @TempDir 正常销毁
        ch.qos.logback.classic.LoggerContext loggerContext =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.stop();

        // info 日志应包含 info 与 error
        assertThat(infoContent).contains("Demo info message for test");
        assertThat(infoContent).contains("Demo error message for test");
        assertThat(infoContent).contains("NO-TRACE");

        // error 日志仅包含 error，绝对不包含普通的 info
        assertThat(errorContent).contains("Demo error message for test");
        assertThat(errorContent).contains("Demo simulated error");
        assertThat(errorContent).doesNotContain("Demo info message for test");
    }
}
