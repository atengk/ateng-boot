package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AtengSecurityAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengSecurityAutoConfiguration 自动装配单元测试")
class AtengSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengSecurityAutoConfiguration.class
            ));

    private final ApplicationContextRunner nonWebContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengSecurityAutoConfiguration.class
            ));

    @Test
    @DisplayName("Servlet Web 环境且默认配置下，自动装配 ActuatorEndpointSecurityFilter")
    void testServletWebAutoConfiguration() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSecurityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ActuatorEndpointSecurityFilter.class);
        });
    }

    @Test
    @DisplayName("当 lockdown-enabled=false 时，跳过安全过滤器装配")
    void testLockdownDisabled() {
        webContextRunner
                .withPropertyValues("ateng.boot.observability.security.lockdown-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSecurityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
                });
    }

    @Test
    @DisplayName("当全局开关 ateng.boot.observability.enabled=false 时，整体跳过安全装配")
    void testGlobalDisabled() {
        webContextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSecurityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
                });
    }

    @Test
    @DisplayName("在非 Web 环境（如纯消费 Worker）下，自动跳过 Web 安全过滤器装配")
    void testNonWebEnvironment() {
        nonWebContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtengSecurityAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
        });
    }

    @Test
    @DisplayName("自动装配后的过滤器完整生效 trusted-proxies、stealth-mode 与自定义 whitelisted-endpoints")
    void testAutoConfiguredSecurityFilterAppliesExtendedProperties() {
        webContextRunner
                .withPropertyValues(
                        "ateng.boot.observability.security.trust-proxy-headers=false",
                        "ateng.boot.observability.security.trusted-proxies[0]=172.16.0.0/12",
                        "ateng.boot.observability.security.allowed-ip-ranges[0]=10.0.0.0/8",
                        "ateng.boot.observability.security.stealth-mode=true",
                        "ateng.boot.observability.security.whitelisted-endpoints[0]=health",
                        "ateng.boot.observability.security.whitelisted-endpoints[1]=custom-status"
                )
                .run(context -> {
                    ActuatorEndpointSecurityFilter filter = context.getBean(ActuatorEndpointSecurityFilter.class);

                    // 1. 自定义白名单放行
                    MockHttpServletRequest reqWhitelist = new MockHttpServletRequest("GET", "/actuator/custom-status");
                    reqWhitelist.setRemoteAddr("198.51.100.1");
                    MockHttpServletResponse resWhitelist = new MockHttpServletResponse();
                    FilterChain chainWhitelist = mock(FilterChain.class);
                    filter.doFilter(reqWhitelist, resWhitelist, chainWhitelist);
                    verify(chainWhitelist).doFilter(reqWhitelist, resWhitelist);

                    // 2. 受信任代理穿透允许访问敏感端点
                    MockHttpServletRequest reqTrusted = new MockHttpServletRequest("GET", "/actuator/env");
                    reqTrusted.setRemoteAddr("172.16.1.20");
                    reqTrusted.addHeader("X-Forwarded-For", "10.1.2.3");
                    MockHttpServletResponse resTrusted = new MockHttpServletResponse();
                    FilterChain chainTrusted = mock(FilterChain.class);
                    filter.doFilter(reqTrusted, resTrusted, chainTrusted);
                    verify(chainTrusted).doFilter(reqTrusted, resTrusted);

                    // 3. 收紧后的 /actuator/info 未授权访问触发 stealth-mode=true 返回 404
                    MockHttpServletRequest reqStealth = new MockHttpServletRequest("GET", "/actuator/info");
                    reqStealth.setRemoteAddr("198.51.100.1");
                    MockHttpServletResponse resStealth = new MockHttpServletResponse();
                    FilterChain chainStealth = mock(FilterChain.class);
                    filter.doFilter(reqStealth, resStealth, chainStealth);
                    verify(chainStealth, never()).doFilter(reqStealth, resStealth);
                    assertThat(resStealth.getStatus()).isEqualTo(404);
                });
    }
}
