package io.github.atengk.boot.core.env;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统基线环境后处理器 SystemEnvironmentPostProcessor 单测
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("系统基线环境后处理器单测")
class SystemEnvironmentPostProcessorTest {

    private final SystemEnvironmentPostProcessor processor = new SystemEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();

    private TimeZone originalTimeZone;
    private String originalUserTimezone;
    private String originalFileEncoding;
    private String originalHeadless;
    private String originalConnectTimeout;
    private String originalReadTimeout;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
        originalUserTimezone = System.getProperty("user.timezone");
        originalFileEncoding = System.getProperty("file.encoding");
        originalHeadless = System.getProperty("java.awt.headless");
        originalConnectTimeout = System.getProperty("sun.net.client.defaultConnectTimeout");
        originalReadTimeout = System.getProperty("sun.net.client.defaultReadTimeout");
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
        restoreProperty("user.timezone", originalUserTimezone);
        restoreProperty("file.encoding", originalFileEncoding);
        restoreProperty("java.awt.headless", originalHeadless);
        restoreProperty("sun.net.client.defaultConnectTimeout", originalConnectTimeout);
        restoreProperty("sun.net.client.defaultReadTimeout", originalReadTimeout);
    }

    private void restoreProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("默认情况下自动注入系统防卫基线与 Asia/Shanghai 时区")
    void shouldApplyDefaultsWhenEnabled() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, application);

        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Shanghai");
        assertThat(System.getProperty("user.timezone")).isEqualTo("Asia/Shanghai");
        assertThat(System.getProperty("file.encoding")).isEqualTo("UTF-8");
        assertThat(System.getProperty("java.awt.headless")).isEqualTo("true");
        assertThat(System.getProperty("sun.net.client.defaultConnectTimeout")).isEqualTo("10000");
        assertThat(System.getProperty("sun.net.client.defaultReadTimeout")).isEqualTo("10000");

        assertThat(environment.getPropertySources().contains(SystemEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("ateng.boot.core.system.timezone")).isEqualTo("Asia/Shanghai");
        assertThat(environment.getProperty("user.timezone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("支持显式配置覆盖时区为 UTC")
    void shouldSupportCustomTimezone() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.system.timezone", "UTC");

        processor.postProcessEnvironment(environment, application);

        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        assertThat(environment.getProperty("ateng.boot.core.system.timezone")).isEqualTo("UTC");
        assertThat(environment.getProperty("user.timezone")).isEqualTo("UTC");
    }

    @Test
    @DisplayName("当系统总开关 enabled 为 false 时，彻底熔断短路退出")
    void shouldBypassWhenSystemDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.system.enabled", "false");

        // 设置一个非默认时区用于校验未被篡改
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

        processor.postProcessEnvironment(environment, application);

        assertThat(TimeZone.getDefault().getID()).isEqualTo("GMT");
        assertThat(environment.getPropertySources().contains(SystemEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("当时区防卫子开关 timezone-guard-enabled 为 false 时，跳过时区校准与属性注入")
    void shouldBypassTimezoneGuardWhenTimezoneGuardDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ateng.boot.core.system.timezone-guard-enabled", "false");

        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

        processor.postProcessEnvironment(environment, application);

        assertThat(TimeZone.getDefault().getID()).isEqualTo("GMT");
        assertThat(environment.getPropertySources().contains(SystemEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
