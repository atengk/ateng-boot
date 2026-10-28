package io.github.atengk.boot.satoken.properties;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import io.github.atengk.boot.satoken.autoconfigure.AtengSaTokenAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaTokenProperties 强类型配置收敛与 SaManagerConfig 双向桥接单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class SaTokenPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSaTokenAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        // 重置 SaManager 中的配置，避免单测上下文跨用例污染
        SaManager.setConfig(new SaTokenConfig());
        io.github.atengk.boot.core.context.UserContextHolder.setUserProvider(null);
        io.github.atengk.boot.core.context.TenantContextHolder.setTenantProvider(null);
        io.github.atengk.boot.core.context.UserContextHolder.clear();
        io.github.atengk.boot.core.context.TenantContextHolder.clear();
    }

    @Test
    @DisplayName("验证 TokenProperties 默认配置值符合生产与合规基线")
    void testDefaultTokenProperties() {
        SaTokenProperties properties = new SaTokenProperties();

        assertThat(properties.getPrimaryLoginType()).isEqualTo("login");
        assertThat(properties.getToken()).isNotNull();
        assertThat(properties.getToken().getTimeout()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.getToken().getActiveTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getToken().isConcurrent()).isTrue();
        assertThat(properties.getToken().isShare()).isTrue();
        assertThat(properties.getToken().getMaxLoginCount()).isEqualTo(12);
        assertThat(properties.getToken().getTokenStyle()).isEqualTo("uuid");
    }

    @Test
    @DisplayName("验证 LoginDefenseProperties 默认配置包含客户端 IP 双轨熔断与白名单")
    void testDefaultLoginDefenseProperties() {
        SaTokenProperties properties = new SaTokenProperties();
        SaTokenProperties.LoginDefenseProperties defense = properties.getLoginDefense();

        assertThat(defense).isNotNull();
        assertThat(defense.isEnabled()).isFalse();
        assertThat(defense.getMaxRetryCount()).isEqualTo(5);
        assertThat(defense.getLockTime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defense.getWindowTime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defense.getIpMaxRetryCount()).isEqualTo(30);
        assertThat(defense.getIpLockTime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defense.getIpWindowTime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defense.getIpWhiteList()).containsExactly("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }

    @Test
    @DisplayName("验证自动装配时默认 Token 配置安全桥接至 SaManager.getConfig()")
    void testDefaultConfigBridging() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SaTokenConfig config = SaManager.getConfig();
            assertThat(config).isNotNull();
            assertThat(config.getTimeout()).isEqualTo(86400L); // 24h
            assertThat(config.getActiveTimeout()).isEqualTo(1800L); // 30m
            assertThat(config.getIsConcurrent()).isTrue();
            assertThat(config.getIsShare()).isTrue();
            assertThat(config.getMaxLoginCount()).isEqualTo(12);
        });
    }

    @Test
    @DisplayName("验证自定义 Duration 配置项正确覆盖并桥接至 SaManager.getConfig()")
    void testCustomConfigBridging() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.satoken.primary-login-type=admin",
                        "ateng.boot.satoken.token.timeout=12h",
                        "ateng.boot.satoken.token.active-timeout=15m",
                        "ateng.boot.satoken.token.is-concurrent=false",
                        "ateng.boot.satoken.token.is-share=false",
                        "ateng.boot.satoken.token.max-login-count=5",
                        "ateng.boot.satoken.token.token-style=tik"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SaTokenProperties properties = context.getBean(SaTokenProperties.class);
                    assertThat(properties.getPrimaryLoginType()).isEqualTo("admin");
                    assertThat(properties.getToken().getTimeout()).isEqualTo(Duration.ofHours(12));
                    assertThat(properties.getToken().getActiveTimeout()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.getToken().isConcurrent()).isFalse();
                    assertThat(properties.getToken().isShare()).isFalse();
                    assertThat(properties.getToken().getMaxLoginCount()).isEqualTo(5);
                    assertThat(properties.getToken().getTokenStyle()).isEqualTo("tik");

                    SaTokenConfig config = SaManager.getConfig();
                    assertThat(config).isNotNull();
                    assertThat(config.getTimeout()).isEqualTo(43200L); // 12h = 43200s
                    assertThat(config.getActiveTimeout()).isEqualTo(900L); // 15m = 900s
                    assertThat(config.getIsConcurrent()).isFalse();
                    assertThat(config.getIsShare()).isFalse();
                    assertThat(config.getMaxLoginCount()).isEqualTo(5);
                    assertThat(config.getTokenStyle()).isEqualTo("tik");
                });
    }
}
