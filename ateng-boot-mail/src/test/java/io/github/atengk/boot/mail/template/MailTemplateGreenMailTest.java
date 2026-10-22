package io.github.atengk.boot.mail.template;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 GreenMail 嵌入式服务器的邮件收发端到端单测
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailTemplateGreenMailTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@atengk.com", "password"));

    private MailTemplate mailTemplate;
    private MailAccountRegistry accountRegistry;
    private MailProperties mailProperties;

    @BeforeEach
    void setUp() {
        int smtpPort = greenMail.getSmtp().getPort();

        mailProperties = new MailProperties();
        mailProperties.setHost("127.0.0.1");
        mailProperties.setPort(smtpPort);
        mailProperties.setUsername("test@atengk.com");
        mailProperties.setPassword("password");
        mailProperties.setSsl(false);
        mailProperties.setDefaultFrom("test@atengk.com");
        mailProperties.setDefaultFromName("阿腾通知系统");

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(smtpPort);
        sender.setUsername("test@atengk.com");
        sender.setPassword("password");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");

        MailAccount account = MailAccount.builder()
                .name("default")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("test@atengk.com")
                .defaultFromName("阿腾通知系统")
                .sender(sender)
                .build();

        accountRegistry = new MailAccountRegistry("default");
        accountRegistry.register(account);

        mailTemplate = new MailTemplate(accountRegistry, mailProperties);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("测试发送纯文本邮件至单收件人并验证 GreenMail 物理报文")
    void testSendTextSingleRecipient() throws Exception {
        MDC.put("traceId", "trace-test-12345");

        MailSendResult result = mailTemplate.sendText("receiver@example.com", "系统欢迎通知", "欢迎加入 Ateng Boot 平台！");

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getMessageId());
        assertTrue(result.getMessageId().contains("trace-test-12345"));
        assertEquals(List.of("receiver@example.com"), result.getRecipients());
        assertNotNull(result.getDuration());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);

        MimeMessage message = messages[0];
        assertEquals("系统欢迎通知", message.getSubject());
        assertEquals("receiver@example.com", message.getAllRecipients()[0].toString());
        assertTrue(message.getContent().toString().contains("欢迎加入 Ateng Boot 平台！"));
        assertTrue(message.getFrom()[0].toString().contains("test@atengk.com"));

        // 验证 Message-ID 头包含 traceId
        String[] messageIdHeaders = message.getHeader("Message-ID");
        assertNotNull(messageIdHeaders);
        assertEquals(1, messageIdHeaders.length);
        assertTrue(messageIdHeaders[0].contains("trace-test-12345"));
    }

    @Test
    @DisplayName("测试发送纯文本邮件至多个收件人")
    void testSendTextMultipleRecipients() throws Exception {
        List<String> recipients = List.of("user1@example.com", "user2@example.com");

        MailSendResult result = mailTemplate.sendText(recipients, "多收件人测试", "群发内容正文");

        assertTrue(result.isSuccess());
        assertEquals(recipients, result.getRecipients());

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
    }

    @Test
    @DisplayName("测试收件人为空时快速失败抛出 MAIL_RECIPIENT_EMPTY 异常")
    void testSendTextEmptyRecipient() {
        MailSendException ex = assertThrows(MailSendException.class, () ->
                mailTemplate.sendText(List.of(), "标题", "内容"));
        assertEquals(MailResultCode.MAIL_RECIPIENT_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("测试指定不存在的发信账号时抛出 MAIL_ACCOUNT_NOT_FOUND 异常")
    void testSendTextAccountNotFound() {
        MailSendException ex = assertThrows(MailSendException.class, () ->
                mailTemplate.sendText("non-existent-account", "test@test.com", "标题", "内容"));
        assertEquals(MailResultCode.MAIL_ACCOUNT_NOT_FOUND.getCode(), ex.getCode());
    }
}
