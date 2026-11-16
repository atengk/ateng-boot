package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.mail.model.AuthMode;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MailSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("MailSafeHealthIndicator 邮件静态账号安全健康探针单元测试")
class MailSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配 MailAccountRegistry 时安全返回 UNKNOWN")
    void testRegistryAbsentReturnsUnknown() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        MailSafeHealthIndicator indicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(indicator.getCacheTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("静态账号列表为空时安全返回 UNKNOWN，即使动态账号缓存非空且异常也不触发物理探活")
    void testEmptyStaticAccountsReturnsUnknownAndIgnoresDynamicAccounts() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default");

        JavaMailSenderImpl brokenDynamicSender = mock(JavaMailSenderImpl.class);
        doThrow(new MailAuthenticationException("dynamic failure")).when(brokenDynamicSender).testConnection();
        MailAccount dynamicAccount = MailAccount.builder()
                .name("tenant-offline")
                .host("smtp.offline.local")
                .port(465)
                .sender(brokenDynamicSender)
                .build();
        registry.getOrCreateDynamic("tenant-offline", dynamicAccount, null);

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(MailAccountRegistry.class, () -> registry);
        context.refresh();

        MailSafeHealthIndicator indicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("staticAccountCount", 0);
        assertThat(health.getDetails()).containsEntry("dynamicAccountCount", 1);
        verify(brokenDynamicSender, times(0)).testConnection();
    }

    @Test
    @DisplayName("静态账号全部连通且动态账号异常时返回 UP，并校验详情脱敏无明文凭据")
    void testStaticAccountsUpIgnoresBrokenDynamicAccount() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default");

        JavaMailSenderImpl okSender1 = mock(JavaMailSenderImpl.class);
        doNothing().when(okSender1).testConnection();
        registry.register(MailAccount.builder()
                .name("default")
                .host("smtp.primary.example.com")
                .port(465)
                .username("mailer-user")
                .password("SUPER_SECRET_PWD_123")
                .accessToken("SECRET_OAUTH_TOKEN_XYZ")
                .authMode(AuthMode.PASSWORD)
                .sender(okSender1)
                .build());

        JavaMailSenderImpl okSender2 = mock(JavaMailSenderImpl.class);
        doNothing().when(okSender2).testConnection();
        registry.register(MailAccount.builder()
                .name("relay")
                .host("relay.internal.local")
                .port(25)
                .authMode(AuthMode.NONE)
                .ssl(false)
                .sender(okSender2)
                .build());

        // 注册一个失效动态账号，验证探针完全跳过动态账号
        JavaMailSenderImpl brokenDynamicSender = mock(JavaMailSenderImpl.class);
        doThrow(new IllegalStateException("broken dynamic")).when(brokenDynamicSender).testConnection();
        registry.getOrCreateDynamic("tenant-dead", MailAccount.builder()
                .name("tenant-dead")
                .host("smtp.dead.local")
                .port(465)
                .sender(brokenDynamicSender)
                .build(), null);

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(MailAccountRegistry.class, () -> registry);
        context.refresh();

        MailSafeHealthIndicator indicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("staticAccountCount", 2)
                .containsEntry("dynamicAccountCount", 1)
                .containsEntry("healthyCount", 2)
                .containsEntry("unhealthyCount", 0);

        String detailsText = health.getDetails().toString();
        assertThat(detailsText).doesNotContain("SUPER_SECRET_PWD_123");
        assertThat(detailsText).doesNotContain("SECRET_OAUTH_TOKEN_XYZ");
        assertThat(detailsText).doesNotContain("mailer-user");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> accounts = (Map<String, Map<String, Object>>) health.getDetails().get("accounts");
        assertThat(accounts).containsKeys("default", "relay");
        assertThat(accounts.get("default")).containsEntry("status", "UP").containsEntry("host", "smtp.primary.example.com");
        assertThat(accounts.get("relay")).containsEntry("status", "UP").containsEntry("authMode", "NONE");

        verify(okSender1, times(1)).testConnection();
        verify(okSender2, times(1)).testConnection();
        verify(brokenDynamicSender, times(0)).testConnection();
    }

    @Test
    @DisplayName("任一静态账号连通失败时返回 DOWN，且错误详情完整脱敏口令、授权码与凭据键值")
    void testStaticAccountFailureReturnsDownWithMaskedSecrets() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default");

        JavaMailSenderImpl okSender = mock(JavaMailSenderImpl.class);
        doNothing().when(okSender).testConnection();
        registry.register(MailAccount.builder()
                .name("default")
                .host("smtp.ok.local")
                .port(465)
                .sender(okSender)
                .build());

        JavaMailSenderImpl badSender = mock(JavaMailSenderImpl.class);
        doThrow(new AuthenticationFailedException(
                "535 AUTH failed for ops-secret@corp.local smtp://ops:RAW_PASS_999@smtp.bad.local password=RAW_PASS_999 accessToken=TOK_ABC_777 token=BEARER_ZZZ"))
                .when(badSender).testConnection();
        registry.register(MailAccount.builder()
                .name("ops")
                .host("smtp.bad.local")
                .port(587)
                .username("ops-secret@corp.local")
                .password("RAW_PASS_999")
                .accessToken("TOK_ABC_777")
                .sender(badSender)
                .build());

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(MailAccountRegistry.class, () -> registry);
        context.refresh();

        MailSafeHealthIndicator indicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("healthyCount", 1).containsEntry("unhealthyCount", 1);

        String serialized = health.getDetails().toString();
        assertThat(serialized).doesNotContain("RAW_PASS_999");
        assertThat(serialized).doesNotContain("TOK_ABC_777");
        assertThat(serialized).doesNotContain("BEARER_ZZZ");
        assertThat(serialized).doesNotContain("ops-secret@corp.local");
        assertThat(serialized).contains("******");
    }

    @Test
    @DisplayName("默认 60s 防刷缓存窗口内多次抓取仅执行一次物理探活，TTL 过期与复位后重新探活")
    void testDefault60sCacheTtlAndConfigurableRefresh() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default");
        AtomicInteger probeCalls = new AtomicInteger(0);
        JavaMailSenderImpl countingSender = mock(JavaMailSenderImpl.class);
        doAnswer(invocation -> {
            probeCalls.incrementAndGet();
            return null;
        }).when(countingSender).testConnection();

        registry.register(MailAccount.builder()
                .name("default")
                .host("smtp.cache.local")
                .port(465)
                .sender(countingSender)
                .build());

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(MailAccountRegistry.class, () -> registry);
        context.refresh();

        // 1. 默认配置：默认 60s 缓存，连续抓取 5 次仅触发 1 次物理探活
        ObservabilityProperties defaultProps = new ObservabilityProperties();
        MailSafeHealthIndicator cachedIndicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class),
                defaultProps
        );
        assertThat(cachedIndicator.getCacheTtl()).isEqualTo(Duration.ofSeconds(60));
        for (int i = 0; i < 5; i++) {
            assertThat(cachedIndicator.health().getStatus()).isEqualTo(Status.UP);
        }
        assertThat(probeCalls.get()).isEqualTo(1);

        // 2. 配置短 TTL 过期后重新穿透探测
        ObservabilityProperties shortTtlProps = new ObservabilityProperties();
        shortTtlProps.getHealth().getMail().setCacheTtl(Duration.ofMillis(80));
        MailSafeHealthIndicator shortTtlIndicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class),
                shortTtlProps
        );
        probeCalls.set(0);
        assertThat(shortTtlIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(shortTtlIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(probeCalls.get()).isEqualTo(1);

        Thread.sleep(120);
        assertThat(shortTtlIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(probeCalls.get()).isEqualTo(2);

        shortTtlIndicator.resetProbeState();
        assertThat(shortTtlIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(probeCalls.get()).isEqualTo(3);

        // 3. 配置 cacheTtl=0s 时每次均实时探测
        ObservabilityProperties zeroTtlProps = new ObservabilityProperties();
        zeroTtlProps.getHealth().getMail().setCacheTtl(Duration.ZERO);
        MailSafeHealthIndicator uncachedIndicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class),
                zeroTtlProps
        );
        probeCalls.set(0);
        uncachedIndicator.health();
        uncachedIndicator.health();
        assertThat(probeCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("物理探活挂起超时受基类超时策略确定性切断并降级返回 UNKNOWN/TIMEOUT")
    void testTimeoutDegradation() throws Exception {
        MailAccountRegistry registry = new MailAccountRegistry("default");
        JavaMailSenderImpl hangingSender = mock(JavaMailSenderImpl.class);
        doAnswer(invocation -> {
            Thread.sleep(500);
            return null;
        }).when(hangingSender).testConnection();

        registry.register(MailAccount.builder()
                .name("default")
                .host("smtp.hang.local")
                .port(465)
                .sender(hangingSender)
                .build());

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(MailAccountRegistry.class, () -> registry);
        context.refresh();

        ObservabilityProperties props = new ObservabilityProperties();
        props.getHealth().setTimeout(Duration.ofMillis(80));
        MailSafeHealthIndicator indicator = new MailSafeHealthIndicator(
                context.getBeanProvider(MailAccountRegistry.class),
                props
        );

        long start = System.currentTimeMillis();
        Health health = indicator.health();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(450);
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }
}
