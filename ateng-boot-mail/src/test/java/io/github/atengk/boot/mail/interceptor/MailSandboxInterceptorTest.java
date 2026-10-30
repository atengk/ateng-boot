package io.github.atengk.boot.mail.interceptor;

import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailSandboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邮件测试安全沙箱拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
class MailSandboxInterceptorTest {

    @Test
    @DisplayName("沙箱未启用时邮件原样放行")
    void testDisabledByDefault() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(false);
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        MailMessage message = MailMessage.builder()
                .to(new ArrayList<>(List.of("external@customer.com")))
                .cc(new ArrayList<>(List.of("cc@customer.com")))
                .bcc(new ArrayList<>(List.of("bcc@customer.com")))
                .subject("Test Subject")
                .text("Hello World")
                .build();

        boolean allowed = interceptor.preSend(message);

        assertThat(allowed).isTrue();
        assertThat(message.getTo()).containsExactly("external@customer.com");
        assertThat(message.getCc()).containsExactly("cc@customer.com");
        assertThat(message.getBcc()).containsExactly("bcc@customer.com");
        assertThat(message.getSubject()).isEqualTo("Test Subject");
    }

    @Test
    @DisplayName("沙箱拦截器优先级为最高优先级 HIGHEST_PRECEDENCE")
    void testOrderHighestPrecedence() {
        MailSandboxProperties props = new MailSandboxProperties();
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);
        assertThat(interceptor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("白名单精确与通配符规则匹配测试")
    void testAllowedRecipientsExactAndWildcard() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setAllowedRecipients(List.of(
                "admin@example.com",
                "*@internal.com",
                "*@*.corp.org"
        ));
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        // 精确匹配
        assertThat(interceptor.isRecipientAllowed("admin@example.com")).isTrue();
        assertThat(interceptor.isRecipientAllowed("Admin@example.com")).isTrue();

        // 域名通配符
        assertThat(interceptor.isRecipientAllowed("developer@internal.com")).isTrue();
        assertThat(interceptor.isRecipientAllowed("Tester <test@internal.com>")).isTrue();

        // 多级通配符
        assertThat(interceptor.isRecipientAllowed("service@dev.corp.org")).isTrue();
        assertThat(interceptor.isRecipientAllowed("service@uat.corp.org")).isTrue();

        // 不匹配
        assertThat(interceptor.isRecipientAllowed("other@example.com")).isFalse();
        assertThat(interceptor.isRecipientAllowed("hacker@malicious.com")).isFalse();
        assertThat(interceptor.isRecipientAllowed(null)).isFalse();
        assertThat(interceptor.isRecipientAllowed("")).isFalse();
    }

    @Test
    @DisplayName("白名单模式：全部收件人均在白名单中时放行物理发送且邮件保持原样")
    void testWhitelistPassThrough() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setAllowedRecipients(List.of("*@internal.com"));
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        MailMessage message = MailMessage.builder()
                .to(new ArrayList<>(List.of("alice@internal.com")))
                .cc(new ArrayList<>(List.of("bob@internal.com")))
                .bcc(new ArrayList<>(List.of("audit@internal.com")))
                .subject("Internal Weekly Report")
                .text("Weekly stats...")
                .build();

        boolean proceed = interceptor.preSend(message);

        assertThat(proceed).isTrue();
        assertThat(message.getTo()).containsExactly("alice@internal.com");
        assertThat(message.getCc()).containsExactly("bob@internal.com");
        assertThat(message.getBcc()).containsExactly("audit@internal.com");
        assertThat(message.getSubject()).isEqualTo("Internal Weekly Report");
    }

    @Test
    @DisplayName("重定向模式：非白名单邮件自动重定向至 redirect-to 并彻底清空 CC/BCC")
    void testRedirectModeWhenRecipientNonWhitelisted() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setAllowedRecipients(List.of("*@internal.com"));
        props.setRedirectTo("mail-trap@company.com");
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        MailMessage message = MailMessage.builder()
                .to(new ArrayList<>(List.of("customer1@gmail.com", "customer2@qq.com")))
                .cc(new ArrayList<>(List.of("external-cc@gmail.com")))
                .bcc(new ArrayList<>(List.of("external-bcc@gmail.com")))
                .subject("Important Invoice")
                .text("Please pay.")
                .build();

        boolean proceed = interceptor.preSend(message);

        assertThat(proceed).isTrue();
        // 目标 To 已被重写为 redirect-to 单一地址
        assertThat(message.getTo()).containsExactly("mail-trap@company.com");
        // CC 与 BCC 彻底清空，防止外部泄密
        assertThat(message.getCc()).isEmpty();
        assertThat(message.getBcc()).isEmpty();
        // 主题追加 [SANDBOX-REDIRECT to: ...] 标头
        assertThat(message.getSubject()).isEqualTo("[SANDBOX-REDIRECT to: customer1@gmail.com,customer2@qq.com] Important Invoice");
        // 校验审计追踪标头
        assertThat(message.getHeaders()).containsEntry(
                MailSandboxInterceptor.HEADER_SANDBOX_REDIRECTED_FROM,
                "customer1@gmail.com,customer2@qq.com"
        );
    }

    @Test
    @DisplayName("重定向模式：非白名单邮件但未配置 redirect-to 时阻断发信抛出 MailSendException")
    void testRedirectModeThrowsWhenRedirectToMissing() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setAllowedRecipients(List.of("*@internal.com"));
        props.setRedirectTo(null); // 未配置重定向邮箱
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        MailMessage message = MailMessage.builder()
                .to(new ArrayList<>(List.of("real-customer@gmail.com")))
                .subject("Invoice")
                .text("Secret Data")
                .build();

        assertThatThrownBy(() -> interceptor.preSend(message))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("redirect-to");
    }

    @Test
    @DisplayName("纯日志模拟模式：设置 HEADER_SANDBOX_LOG_ONLY 标头且不发起外部重定向改写")
    void testLogOnlyMode() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setLogOnly(true);
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        MailMessage message = MailMessage.builder()
                .to(new ArrayList<>(List.of("user@external.com")))
                .cc(new ArrayList<>(List.of("manager@external.com")))
                .subject("System Notification")
                .text("Simulated content")
                .build();

        boolean proceed = interceptor.preSend(message);

        assertThat(proceed).isTrue();
        assertThat(message.getHeaders()).containsEntry(MailSandboxInterceptor.HEADER_SANDBOX_LOG_ONLY, "true");
        // 纯日志模式保留原收件人信息供日志观察
        assertThat(message.getTo()).containsExactly("user@external.com");
        assertThat(message.getCc()).containsExactly("manager@external.com");
    }

    @Test
    @DisplayName("防御不可变集合：传入不可变 List.of 与 Map.of 时重定向与纯日志模式均安全无异常")
    void testImmutableCollectionsDefensiveHandling() {
        MailSandboxProperties props = new MailSandboxProperties();
        props.setEnabled(true);
        props.setAllowedRecipients(List.of("*@internal.com"));
        props.setRedirectTo("mail-trap@company.com");
        MailSandboxInterceptor interceptor = new MailSandboxInterceptor(props);

        // 构造包含不可变 List.of 与 Map.of 的邮件对象
        MailMessage message = MailMessage.builder()
                .to(List.of("external-customer@gmail.com"))
                .cc(List.of("external-cc@gmail.com"))
                .bcc(List.of("external-bcc@gmail.com"))
                .headers(Map.of("X-Custom-Trace", "12345"))
                .subject("Invoice")
                .text("Invoice Body")
                .build();

        // 验证重定向模式清空不可变 Cc/Bcc 绝不抛出 UnsupportedOperationException
        boolean proceed = interceptor.preSend(message);
        assertThat(proceed).isTrue();
        assertThat(message.getTo()).containsExactly("mail-trap@company.com");
        assertThat(message.getCc()).isEmpty();
        assertThat(message.getBcc()).isEmpty();
        assertThat(message.getHeaders())
                .containsEntry("X-Custom-Trace", "12345")
                .containsEntry(MailSandboxInterceptor.HEADER_SANDBOX_REDIRECTED_FROM, "external-customer@gmail.com");

        // 验证纯日志模式向不可变 headers 注入标头绝不抛出 UnsupportedOperationException
        props.setLogOnly(true);
        MailMessage logOnlyMessage = MailMessage.builder()
                .to(List.of("external@gmail.com"))
                .headers(Map.of("X-Original-App", "pay-service"))
                .subject("Payment Notice")
                .text("Simulated")
                .build();

        boolean logOnlyProceed = interceptor.preSend(logOnlyMessage);
        assertThat(logOnlyProceed).isTrue();
        assertThat(logOnlyMessage.getHeaders())
                .containsEntry("X-Original-App", "pay-service")
                .containsEntry(MailSandboxInterceptor.HEADER_SANDBOX_LOG_ONLY, "true");
    }
}
