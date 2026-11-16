package io.github.atengk.boot.mail.template;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import io.github.atengk.boot.mail.event.MailAccountEvictEvent;
import io.github.atengk.boot.mail.event.MailQuotaExceededEvent;
import io.github.atengk.boot.mail.event.MailSendEvent;
import io.github.atengk.boot.mail.event.MailSendFailedEvent;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.guard.InMemoryMailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.InMemoryMailIdempotentGuard;
import io.github.atengk.boot.mail.guard.InMemoryMailRateLimiter;
import io.github.atengk.boot.mail.guard.MailDailyQuotaBreaker;
import io.github.atengk.boot.mail.guard.MailIdempotentGuard;
import io.github.atengk.boot.mail.guard.MailRateLimiter;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.listener.MailAccountEvictListener;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailPriority;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.MailSendResultItem;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;
import io.github.atengk.boot.mail.properties.MailSandboxProperties;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.renderer.FreemarkerMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.ThymeleafMailTemplateRenderer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 GreenMail 嵌入式服务器的邮件收发端到端单测
 *
 * @author Ateng
 * @since 2026-10-22
 */
class MailTemplateGreenMailTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("test@atengk.com", "password")
                    .withUser("ops@atengk.com", "password"));

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
        props.put("mail.smtp.quitwait", "false");
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

        // 注册专职运维告警的 ops 账号
        MailAccount opsAccount = MailAccount.builder()
                .name("ops")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("ops@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("ops@atengk.com")
                .defaultFromName("运维告警中心")
                .sender(sender)
                .build();
        accountRegistry.register(opsAccount);

        mailTemplate = new MailTemplate(accountRegistry, mailProperties, new SimpleMailTemplateRenderer());
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        LocaleContextHolder.resetLocaleContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        accountRegistry.clearDynamic();
        if (mailTemplate != null) {
            mailTemplate.destroy();
        }
        greenMail.reset();
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
    @DisplayName("测试发送富文本 HTML 邮件并验证 Content-Type 标头与内容")
    void testSendHtmlSingleRecipient() throws Exception {
        String htmlBody = "<h1 style='color:red;'>重要告警</h1><p>磁盘使用率超过 90%！</p>";

        MailSendResult result = mailTemplate.sendHtml("ops@example.com", "服务器告警", htmlBody);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertEquals("服务器告警", message.getSubject());
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("<h1 style='color:red;'>重要告警</h1>"));
    }

    @Test
    @DisplayName("测试发送基于类路径模板渲染的邮件并验证占位符替换")
    void testSendTemplateWithClasspathResource() throws Exception {
        Map<String, Object> model = Map.of("username", "钱七", "platform", "Ateng Boot");

        MailSendResult result = mailTemplate.sendTemplate("client@example.com", "欢迎加入", "welcome", model);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("欢迎, 钱七!"));
        assertTrue(message.getContent().toString().contains("感谢您注册 Ateng Boot 平台"));
    }

    @Test
    @DisplayName("测试发送基于内联模板字符串渲染的邮件")
    void testSendTemplateWithInlineString() throws Exception {
        String tpl = "<p>尊敬的 ${name}，您的验证码为 <b>${code}</b>，有效期 5 分钟。</p>";
        Map<String, Object> model = Map.of("name", "周八", "code", 665544);

        MailSendResult result = mailTemplate.sendTemplate("inline@example.com", "验证码通知", tpl, model);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        assertTrue(messages[0].getContent().toString().contains("尊敬的 周八，您的验证码为 <b>665544</b>"));
    }

    @Test
    @DisplayName("测试集成 Thymeleaf 模板引擎通过 GreenMail 端到端发送与报文断言")
    void testSendTemplateWithThymeleafEngine() throws Exception {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode("HTML");
        engine.setTemplateResolver(resolver);

        ThymeleafMailTemplateRenderer thymeleafRenderer = new ThymeleafMailTemplateRenderer(engine);
        MailTemplate customTemplate = new MailTemplate(accountRegistry, mailProperties, thymeleafRenderer,
                new io.github.atengk.boot.mail.resolver.TenantMailAccountResolver(), null, null, null, null);

        Map<String, Object> model = Map.of("username", "钱七", "platform", "Ateng Boot");
        MailSendResult result = customTemplate.sendTemplate("thymeleaf-user@example.com", "Thymeleaf 欢迎函", "thymeleaf-welcome", model);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertEquals("Thymeleaf 欢迎函", message.getSubject());
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("欢迎, 钱七!"));
    }

    @Test
    @DisplayName("测试集成 FreeMarker 模板引擎通过 GreenMail 端到端发送与报文断言")
    void testSendTemplateWithFreemarkerEngine() throws Exception {
        freemarker.template.Configuration cfg = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_32);
        freemarker.cache.StringTemplateLoader loader = new freemarker.cache.StringTemplateLoader();
        loader.putTemplate("notice.ftl", "<h1>通知: ${title}</h1><p>${content}</p>");
        cfg.setTemplateLoader(loader);
        cfg.setDefaultEncoding("UTF-8");

        FreemarkerMailTemplateRenderer freemarkerRenderer = new FreemarkerMailTemplateRenderer(cfg);
        MailTemplate customTemplate = new MailTemplate(accountRegistry, mailProperties, freemarkerRenderer,
                new io.github.atengk.boot.mail.resolver.TenantMailAccountResolver(), null, null, null, null);

        Map<String, Object> model = Map.of("title", "系统升级通知", "content", "系统将于今晚执行滚动发布");
        MailSendResult result = customTemplate.sendTemplate("freemarker-user@example.com", "系统维护提醒", "notice", model);

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertEquals("系统维护提醒", message.getSubject());
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("通知: 系统升级通知"));
        assertTrue(message.getContent().toString().contains("系统将于今晚执行滚动发布"));
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
    @DisplayName("测试发送富文本 HTML 邮件至多个收件人")
    void testSendHtmlMultipleRecipients() throws Exception {
        List<String> recipients = List.of("userA@example.com", "userB@example.com");

        MailSendResult result = mailTemplate.sendHtml(recipients, "多收件人HTML", "<b>群发通知</b>");

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

    @Test
    @DisplayName("测试发送带中文附件与普通文件的多附件邮件并验证物理报文")
    void testSendMailMessageWithAttachments() throws Exception {
        byte[] excelContent = "xlsx fake binary content".getBytes(StandardCharsets.UTF_8);
        byte[] textContent = "hello text attachment".getBytes(StandardCharsets.UTF_8);

        MailSendResult result = mailTemplate.send(msg -> msg
                .to("attach-recipient@example.com")
                .subject("月度业务与财务汇总")
                .text("请查收附件详情。")
                .attach("财务报表_202610.xlsx", excelContent)
                .attach("readme.txt", textContent));

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];

        assertEquals("月度业务与财务汇总", message.getSubject());
        assertTrue(message.getContent() instanceof MimeMultipart);
        MimeMultipart multipart = (MimeMultipart) message.getContent();

        boolean foundExcel = false;
        boolean foundTxt = false;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String rawFileName = part.getFileName();
            if (rawFileName != null) {
                String decodedName = MimeUtility.decodeText(rawFileName);
                if (decodedName.contains("财务报表_202610.xlsx")) {
                    foundExcel = true;
                } else if (decodedName.contains("readme.txt")) {
                    foundTxt = true;
                }
            }
        }

        assertTrue(foundExcel, "应在邮件报文中解析出中文附件 财务报表_202610.xlsx");
        assertTrue(foundTxt, "应在邮件报文中解析出附件 readme.txt");
    }

    @Test
    @DisplayName("测试发送带正交内嵌图片 CID 资源的富文本 HTML 邮件")
    void testSendMailMessageWithInlineResource() throws Exception {
        byte[] imageBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String htmlContent = "<h1>企业通知</h1><img src='cid:companyLogo' alt='Logo'/><p>欢迎体验。</p>";

        MailSendResult result = mailTemplate.send(msg -> msg
                .to("inline-user@example.com")
                .subject("带图通知")
                .html(htmlContent)
                .inline("companyLogo", imageBytes, "image/png"));

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];

        boolean foundCid = findPart(message, part -> {
            try {
                String[] contentIdHeader = part.getHeader("Content-ID");
                if (contentIdHeader != null && contentIdHeader.length > 0) {
                    return contentIdHeader[0].contains("companyLogo");
                }
            } catch (Exception ignored) {
            }
            return false;
        });

        assertTrue(foundCid, "应在邮件多部分中正确找到绑定 cid:companyLogo 的内嵌图片资源");
    }

    private boolean findPart(Part part, PartPredicate predicate) throws Exception {
        if (predicate.test(part)) {
            return true;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                if (findPart(mp.getBodyPart(i), predicate)) {
                    return true;
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface PartPredicate {
        boolean test(Part part) throws Exception;
    }

    @Test
    @DisplayName("测试函数式 DSL 构造邮件并验证抄送、密送与自定义标头")
    void testSendMailMessageDslWithCcBccAndHeaders() throws Exception {
        MailSendResult result = mailTemplate.send(msg -> msg
                .from("custom-sender@atengk.com", "自定义发件人")
                .to("to-user@example.com")
                .cc("cc-user@example.com")
                .bcc("bcc-user@example.com")
                .replyTo("reply@atengk.com")
                .subject("DSL 全能测试")
                .text("这是通过 DSL 构建的测试邮件")
                .header("X-Custom-Trace", "trace-xyz-999"));

        assertTrue(result.isSuccess());
        // GreenMail 会为每一个收件人/抄送/密送投递一份副本（总计 3 份）
        assertTrue(greenMail.waitForIncomingEmail(5000, 3));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertTrue(messages.length >= 3);
        MimeMessage sampleMsg = messages[0];

        assertEquals("DSL 全能测试", sampleMsg.getSubject());
        assertEquals("reply@atengk.com", sampleMsg.getReplyTo()[0].toString());
        assertEquals("trace-xyz-999", sampleMsg.getHeader("X-Custom-Trace")[0]);
        String fromHeader = MimeUtility.decodeText(sampleMsg.getFrom()[0].toString());
        assertTrue(fromHeader.contains("自定义发件人"));
        assertTrue(fromHeader.contains("custom-sender@atengk.com"));
    }

    @Test
    @DisplayName("测试单个附件大小超限时 Fail-Fast 抛出 MAIL_ATTACHMENT_SIZE_EXCEEDED")
    void testMaxAttachmentSizeExceeded() {
        mailProperties.setMaxAttachmentSize(DataSize.ofBytes(50));

        byte[] largeData = new byte[100]; // 100 > 50

        MailSendException ex = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("overflow@example.com")
                        .subject("附件超限测试")
                        .text("正文")
                        .attach("big-file.bin", largeData)));

        assertEquals(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("big-file.bin"));
    }

    @Test
    @DisplayName("测试未知长度流式附件超限时在读取阶段被 BoundedInputStream 拦截抛出 MAIL_ATTACHMENT_SIZE_EXCEEDED")
    void testMaxAttachmentSizeExceededWithUnknownStreamSize() {
        mailProperties.setMaxAttachmentSize(DataSize.ofBytes(50));

        byte[] largeData = new byte[100]; // 100 > 50
        ByteArrayInputStream in = new ByteArrayInputStream(largeData);
        // 使用 InputStream 构建附件，初始 size 为 null
        MailAttachment streamAttachment = MailAttachment.of("stream-file.bin", in);
        assertNull(streamAttachment.getSize());

        MailSendException ex = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("stream-overflow@example.com")
                        .subject("流式超限测试")
                        .text("正文")
                        .attachment(streamAttachment)));

        assertEquals(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("stream-file.bin"));
    }

    @Test
    @DisplayName("测试邮件总体积超限时 Fail-Fast 抛出 MAIL_TOTAL_SIZE_EXCEEDED")
    void testMaxTotalSizeExceeded() {
        mailProperties.setMaxAttachmentSize(DataSize.ofBytes(100));
        mailProperties.setMaxTotalSize(DataSize.ofBytes(120));

        byte[] part1 = new byte[70]; // 单个未超限
        byte[] part2 = new byte[70]; // 单个未超限，但 70 + 70 = 140 > 120

        MailSendException ex = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("overflow@example.com")
                        .subject("总大小超限测试")
                        .text("正文")
                        .attach("part1.bin", part1)
                        .attach("part2.bin", part2)));

        assertEquals(MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("测试发信生命周期资源清理（确定性 close 机制）")
    void testDeterministicResourceCleanup() {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("stream attachment".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        mailTemplate.send(msg -> msg
                .to("cleanup-test@example.com")
                .subject("资源清理测试")
                .text("测试流关闭")
                .attach("stream.txt", bais));

        assertTrue(closed.get(), "发信完成后挂载的附件流必须被确定性关闭");
    }

    @Test
    @DisplayName("测试指定多命名账号发信及发件人身份物理隔离")
    void testSendViaMultipleNamedAccounts() throws Exception {
        // 使用 ops 账号发信
        MailSendResult resultOps = mailTemplate.send(msg -> msg
                .account("ops")
                .to("recipient-ops@example.com")
                .subject("集群报警")
                .text("CPU 持续 95% 异常告警"));

        assertTrue(resultOps.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(1, msgs.length);
        String fromHeader = MimeUtility.decodeText(msgs[0].getFrom()[0].toString());
        assertTrue(fromHeader.contains("运维告警中心"), "应为 ops 账号的发件人昵称");
        assertTrue(fromHeader.contains("ops@atengk.com"), "应为 ops 账号的发件人邮箱");
    }

    @Test
    @DisplayName("测试基于 TenantContextHolder 的租户自动寻址与未命中平滑回退")
    void testTenantAdaptiveRoutingWithFallback() throws Exception {
        // 1. 设置租户上下文为 "ops"，发信未显式指定账号时应自适应路由至 "ops"
        TenantContextHolder.setTenantId("ops");

        MailSendResult result1 = mailTemplate.sendText("tenant-ops-user@example.com", "租户通知", "租户路由内容");
        assertTrue(result1.isSuccess());

        // 2. 设置未在注册表中配置的未知租户，应安全平滑回退至 default 账号而不引发崩溃
        TenantContextHolder.setTenantId("unknown-tenant-888");

        MailSendResult result2 = mailTemplate.sendText("fallback-user@example.com", "回退通知", "平滑回退默认账号");
        assertTrue(result2.isSuccess());

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));

        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(2, msgs.length);
        String from1 = MimeUtility.decodeText(msgs[0].getFrom()[0].toString());
        assertTrue(from1.contains("ops@atengk.com"), "命中租户上下文 ops 时应自动路由至 ops 发件账号");

        String from2 = MimeUtility.decodeText(msgs[1].getFrom()[0].toString());
        assertTrue(from2.contains("test@atengk.com"), "未配置租户账号时必须平滑回退至默认账号");
        assertTrue(from2.contains("阿腾通知系统"));
    }

    @Test
    @DisplayName("测试编程式动态账号发信及 LRU 缓存复用底层物理 Sender")
    void testDynamicAccountWithLruCache() throws Exception {
        int smtpPort = greenMail.getSmtp().getPort();

        // 构造动态账号（未预先绑定物理 Sender）
        MailAccount dynamicAccount = MailAccount.builder()
                .name("dynamic-tenant-finance")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("finance@dynamic.atengk.com")
                .defaultFromName("动态财务专线")
                .build();

        assertEquals(0, accountRegistry.dynamicSize());

        // 1. 首次使用动态账号发信
        MailSendResult result1 = mailTemplate.forDynamicAccount(dynamicAccount)
                .sendText("client@dynamic.com", "电子对账单", "请查收您的本月对账单。");
        assertTrue(result1.isSuccess());

        // 验证动态账号已成功进入受管 LRU 缓存
        assertEquals(1, accountRegistry.dynamicSize());

        // 2. 第二次发信，应复用 LRU 缓存中的底层 Sender
        MailSendResult result2 = mailTemplate.forDynamicAccount(dynamicAccount)
                .send(msg -> msg
                        .to("client2@dynamic.com")
                        .subject("第二份对账单")
                        .text("第二份对账单内容"));

        assertTrue(result2.isSuccess());
        assertEquals(1, accountRegistry.dynamicSize(), "相同指纹的动态账号必须复用 LRU 缓存，不得重复膨胀");

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(2, msgs.length);
        String from = MimeUtility.decodeText(msgs[0].getFrom()[0].toString());
        assertTrue(from.contains("动态财务专线"));
        assertTrue(from.contains("finance@dynamic.atengk.com"));
    }

    @Test
    @DisplayName("测试异步发送纯文本邮件并验证 CompletableFuture 与 GreenMail 物理报文")
    void testSendTextAsyncSingleRecipient() throws Exception {
        MDC.put("traceId", "trace-async-text-123");

        CompletableFuture<MailSendResult> future = mailTemplate.sendTextAsync(
                "async-receiver@example.com", "异步欢迎通知", "欢迎加入 Ateng Boot 异步发信体系！");

        assertNotNull(future);
        MailSendResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getMessageId());
        assertTrue(result.getMessageId().contains("trace-async-text-123"));
        assertEquals(List.of("async-receiver@example.com"), result.getRecipients());
        assertNotNull(result.getDuration());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);

        MimeMessage message = messages[0];
        assertEquals("异步欢迎通知", message.getSubject());
        assertEquals("async-receiver@example.com", message.getAllRecipients()[0].toString());
        assertTrue(message.getContent().toString().contains("欢迎加入 Ateng Boot 异步发信体系！"));
        assertTrue(message.getHeader("Message-ID")[0].contains("trace-async-text-123"));
    }

    @Test
    @DisplayName("测试异步发送富文本 HTML 邮件并验证 Content-Type 标头")
    void testSendHtmlAsyncSingleRecipient() throws Exception {
        String htmlBody = "<h2 style='color:green;'>系统恢复</h2><p>所有微服务集群已恢复正常运行。</p>";

        CompletableFuture<MailSendResult> future = mailTemplate.sendHtmlAsync(
                "async-ops@example.com", "系统恢复通知", htmlBody);

        MailSendResult result = future.join();
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertEquals("系统恢复通知", message.getSubject());
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("所有微服务集群已恢复正常运行。"));
    }

    @Test
    @DisplayName("测试异步发送模板渲染邮件并验证插值占位符替换")
    void testSendTemplateAsync() throws Exception {
        Map<String, Object> model = Map.of("username", "异步李四", "platform", "Ateng Boot Mail");

        CompletableFuture<MailSendResult> future = mailTemplate.sendTemplateAsync(
                "async-client@example.com", "异步欢迎模板", "welcome", model);

        MailSendResult result = future.join();
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        MimeMessage message = messages[0];
        assertTrue(message.getContentType().contains("text/html"));
        assertTrue(message.getContent().toString().contains("欢迎, 异步李四!"));
    }

    @Test
    @DisplayName("测试基于函数式 DSL 异步发送邮件")
    void testSendAsyncFunctionalDsl() throws Exception {
        CompletableFuture<MailSendResult> future = mailTemplate.sendAsync(msg -> msg
                .to("dsl-async@example.com")
                .subject("DSL 异步标题")
                .text("DSL 异步正文内容"));

        MailSendResult result = future.join();
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);
        assertEquals("DSL 异步标题", messages[0].getSubject());
    }

    @Test
    @DisplayName("测试高并发异步发信与 CompletableFuture 全部就绪")
    void testConcurrentAsyncSending() throws Exception {
        int mailCount = 8;
        List<CompletableFuture<MailSendResult>> futures = new ArrayList<>();

        for (int i = 0; i < mailCount; i++) {
            final int index = i;
            futures.add(mailTemplate.sendTextAsync(
                    "concurrent-" + index + "@example.com",
                    "并发异步通知 " + index,
                    "并发异步内容批次 #" + index));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (CompletableFuture<MailSendResult> future : futures) {
            MailSendResult res = future.get();
            assertNotNull(res);
            assertTrue(res.isSuccess());
        }

        assertTrue(greenMail.waitForIncomingEmail(10000, mailCount));
        assertEquals(mailCount, greenMail.getReceivedMessages().length);
    }

    @Test
    @DisplayName("测试四维上下文透明穿透与多租户自适应动态寻址")
    void testAsync4DContextPropagationAndTenantRouting() throws Exception {
        // 1. 在当前父调用线程注入四维上下文
        String traceId = "trace-4d-async-uuid-999";
        MDC.put("traceId", traceId);
        UserContextHolder.set("user-tenant-admin");
        TenantContextHolder.setTenantId("ops");

        // 2. 异步发起发信（未显式指定发信账号，需跨线程透明穿透 tenantId 自动路由到 ops 账号）
        CompletableFuture<MailSendResult> future = mailTemplate.sendTextAsync(
                "tenant-async-receiver@example.com", "4D上下文异步测试", "测试四维上下文透明穿透");

        // 3. 父线程立即销毁所有上下文现场，验证异步线程持有的快照不受父线程提前清理影响
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        // 4. 等待异步任务执行完成
        MailSendResult result = future.join();
        assertTrue(result.isSuccess());
        assertTrue(result.getMessageId().contains(traceId), "Message-ID 头必须捕获并注入父线程的 traceId");

        // 5. 验证 GreenMail 物理接收报文已自动路由到 ops 发信账号
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(1, msgs.length);

        String from = MimeUtility.decodeText(msgs[0].getFrom()[0].toString());
        assertTrue(from.contains("ops@atengk.com"), "四维上下文 TenantId 必须跨线程穿透并驱动动态账号路由命中 ops");
        assertTrue(from.contains("运维告警中心"));

        // 6. 验证当前主线程现场绝对纯净，未发生线程污染
        assertNull(MDC.get("traceId"), "主线程 MDC 现场必须保持纯净");
        assertNull(UserContextHolder.get(), "主线程 User 现场必须保持纯净");
        assertNull(TenantContextHolder.getTenantId(), "主线程 Tenant 现场必须保持纯净");
    }

    @Test
    @DisplayName("测试异步发信执行器线程名前缀标识及停机销毁清理")
    void testAsyncExecutorThreadPrefixAndShutdownCleanup() throws Exception {
        AtomicReference<String> asyncThreadName = new AtomicReference<>();
        MailSendInterceptor interceptor = message -> {
            asyncThreadName.set(Thread.currentThread().getName());
            return true;
        };

        MailTemplate customTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null, null, List.of(interceptor)
        );

        try {
            CompletableFuture<MailSendResult> future = customTemplate.sendTextAsync(
                    "thread-test@example.com", "线程名验证", "正文内容");
            MailSendResult result = future.join();
            assertTrue(result.isSuccess());

            assertNotNull(asyncThreadName.get(), "拦截器必须捕获到异步发信线程");
            assertTrue(asyncThreadName.get().startsWith("mail-async-"),
                    "异步执行器线程名必须以 mail-async- 为前缀，实际为: " + asyncThreadName.get());

            // 验证执行器为受管 ThreadPoolTaskExecutor 且未关闭
            assertTrue(customTemplate.getExecutor() instanceof ThreadPoolTaskExecutor);
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) customTemplate.getExecutor();
            assertFalse(taskExecutor.getThreadPoolExecutor().isShutdown(), "销毁前底层线程池必须活跃");

            // 停机销毁验证
            customTemplate.destroy();
            assertTrue(taskExecutor.getThreadPoolExecutor().isShutdown(), "调用 destroy 后底层线程池必须被物理关闭");
        } finally {
            customTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试动态编程式账号的异步发信门面与 LRU 缓存复用")
    void testDynamicMailSessionAsync() throws Exception {
        int smtpPort = greenMail.getSmtp().getPort();

        MailAccount dynamicAccount = MailAccount.builder()
                .name("dynamic-async-sales")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("sales@dynamic.atengk.com")
                .defaultFromName("动态销售专线")
                .build();

        CompletableFuture<MailSendResult> future = mailTemplate.forDynamicAccount(dynamicAccount)
                .sendTextAsync("client-async@dynamic.com", "异步报价单", "请查收您的实时报价单。");

        MailSendResult result = future.join();
        assertTrue(result.isSuccess());
        assertEquals(1, accountRegistry.dynamicSize());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(1, msgs.length);
        String from = MimeUtility.decodeText(msgs[0].getFrom()[0].toString());
        assertTrue(from.contains("动态销售专线"));
        assertTrue(from.contains("sales@dynamic.atengk.com"));
    }

    @Test
    @DisplayName("测试异步发信异常受控传播至 CompletableFuture")
    void testAsyncSendFailurePropagation() {
        MailMessage invalidMessage = new MailMessage().subject("无收件人邮件");

        CompletableFuture<MailSendResult> future = mailTemplate.sendAsync(invalidMessage);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(MailSendException.class, ex.getCause());
        MailSendException mailEx = (MailSendException) ex.getCause();
        assertEquals(MailResultCode.MAIL_RECIPIENT_EMPTY, mailEx.getResultCode());
    }

    @Test
    @DisplayName("测试事务提交后发信(sendAfterCommit)：仅在事务物理提交后发出邮件")
    void testSendAfterCommitWithTransactionCommitted() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            mailTemplate.sendTextAfterCommit("tx-success@example.com", "事务成功通知", "订单已创建成功！");

            // 尚未触发 afterCommit，此时不应发出任何物理邮件
            assertEquals(0, greenMail.getReceivedMessages().length);

            // 模拟 Spring 事务提交完成
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }

            // 验证已物理发信成功
            assertTrue(greenMail.waitForIncomingEmail(5000, 1));
            MimeMessage[] msgs = greenMail.getReceivedMessages();
            assertEquals(1, msgs.length);
            assertEquals("事务成功通知", msgs[0].getSubject());
            assertEquals("tx-success@example.com", msgs[0].getAllRecipients()[0].toString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试事务回滚发信(sendAfterCommit)：事务回滚自动取消发信，邮件数为0")
    void testSendAfterCommitWithTransactionRolledBack() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            mailTemplate.sendTextAfterCommit("tx-fail@example.com", "幽灵邮件测试", "该邮件绝不能被发送！");

            // 尚未触发提交或回滚
            assertEquals(0, greenMail.getReceivedMessages().length);

            // 模拟 Spring 事务发生异常回滚
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            // 等待并验证绝对没有收到任何邮件
            assertFalse(greenMail.waitForIncomingEmail(1000, 1));
            assertEquals(0, greenMail.getReceivedMessages().length, "事务回滚时必须彻底取消发信，杜绝幽灵邮件");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试事务提交后异步发信(sendAsyncAfterCommit)：提交后在受管线程池中成功发送")
    void testSendAsyncAfterCommitWithTransactionCommitted() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            mailTemplate.sendTextAsyncAfterCommit("tx-async@example.com", "事务异步通知", "异步发信测试");

            assertEquals(0, greenMail.getReceivedMessages().length);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }

            assertTrue(greenMail.waitForIncomingEmail(5000, 1));
            MimeMessage[] msgs = greenMail.getReceivedMessages();
            assertEquals(1, msgs.length);
            assertEquals("事务异步通知", msgs[0].getSubject());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试无事务上下文时 sendAfterCommit 平滑降级立即物理发信")
    void testSendAfterCommitWithoutTransactionFallback() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        mailTemplate.sendTextAfterCommit("no-tx@example.com", "无事务通知", "无事务降级发信");

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(1, msgs.length);
        assertEquals("无事务通知", msgs[0].getSubject());
    }

    @Test
    @DisplayName("测试物理发信网络抖动时联动 RetryUtil 执行指数退避重试并成功恢复")
    void testRetryOnTemporaryFailureAndRecover() throws Exception {
        mailProperties.getRetry().setEnabled(true);
        mailProperties.getRetry().setMaxAttempts(3);
        mailProperties.getRetry().setDelay(Duration.ofMillis(50));
        mailProperties.getRetry().setMultiplier(1.5);
        mailProperties.getRetry().setJitter(false);

        int smtpPort = greenMail.getSmtp().getPort();
        JavaMailSenderImpl realSender = new JavaMailSenderImpl();
        realSender.setHost("127.0.0.1");
        realSender.setPort(smtpPort);
        realSender.setUsername("test@atengk.com");
        realSender.setPassword("password");

        AtomicInteger attemptCounter = new AtomicInteger(0);
        MailAccount retryAccount = MailAccount.builder()
                .name("retry-account")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("retry@atengk.com")
                .sender(new JavaMailSenderImpl() {
                    @Override
                    public void send(jakarta.mail.internet.MimeMessage mimeMessage) {
                        if (attemptCounter.incrementAndGet() < 2) {
                            throw new org.springframework.mail.MailSendException("Connection reset by peer (mock network glitch)");
                        }
                        realSender.send(mimeMessage);
                    }
                })
                .build();
        accountRegistry.register(retryAccount);

        MailSendResult result = mailTemplate.send(msg -> msg
                .account("retry-account")
                .to("retry-user@example.com")
                .subject("重试成功通知")
                .text("网络抖动后重试成功"));

        assertTrue(result.isSuccess());
        assertEquals(2, attemptCounter.get(), "应在第 2 次尝试重试成功");

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(1, msgs.length);
        assertEquals("重试成功通知", msgs[0].getSubject());
    }

    @Test
    @DisplayName("测试物理发信最终失败时触发三重安全网：脱敏日志、事件广播与 SPI 回调")
    void testFailureSafetyNetTriggered() {
        AtomicReference<MailMessage> failedMsgHolder = new AtomicReference<>();
        AtomicReference<Throwable> failedCauseHolder = new AtomicReference<>();

        MailSendFailureHandler failureHandler = (msg, cause) -> {
            failedMsgHolder.set(msg);
            failedCauseHolder.set(cause);
        };

        AtomicReference<MailSendFailedEvent> eventHolder = new AtomicReference<>();
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof MailSendFailedEvent failedEvent) {
                eventHolder.set(failedEvent);
            }
        };

        MailTemplate customTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                eventPublisher, List.of(failureHandler));

        MailAccount deadAccount = MailAccount.builder()
                .name("dead-account")
                .host("127.0.0.1")
                .port(59999)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .sender(new JavaMailSenderImpl() {
                    @Override
                    public void send(jakarta.mail.internet.MimeMessage mimeMessage) {
                        throw new org.springframework.mail.MailSendException("Connection refused");
                    }
                })
                .build();
        accountRegistry.register(deadAccount);

        MailSendException ex = assertThrows(MailSendException.class, () ->
                customTemplate.send(msg -> msg
                        .account("dead-account")
                        .to("dead-target@example.com")
                        .subject("失败测试")
                        .text("必然失败内容")));

        assertNotNull(ex);

        // 验证 SPI 回调被成功触发
        assertNotNull(failedMsgHolder.get(), "三重安全网之 MailSendFailureHandler SPI 必须被成功触发");
        assertEquals("dead-account", failedMsgHolder.get().getAccount());
        assertNotNull(failedCauseHolder.get(), "SPI 回调必须完整保留原始失败因果链");

        // 验证 Spring 事件被广播
        assertNotNull(eventHolder.get(), "三重安全网之 MailSendFailedEvent 事件必须被成功广播");
        assertEquals("dead-account", eventHolder.get().getMessage().getAccount());
        assertEquals(failedCauseHolder.get(), eventHolder.get().getCause());

        customTemplate.destroy();
    }

    @Test
    @DisplayName("测试发信生命周期事件正常发布：MailSendingEvent 与 MailSentEvent")
    void testLifecycleEventsPublished() throws Exception {
        AtomicBoolean sendingEventFired = new AtomicBoolean(false);
        AtomicReference<MailSentEvent> sentEventHolder = new AtomicReference<>();

        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof MailSendingEvent) {
                sendingEventFired.set(true);
            } else if (event instanceof MailSentEvent sentEvent) {
                sentEventHolder.set(sentEvent);
            }
        };

        MailTemplate eventTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                eventPublisher, List.of());

        MailSendResult result = eventTemplate.sendText("event-user@example.com", "生命周期测试", "测试发信事件发布");

        assertTrue(result.isSuccess());
        assertTrue(sendingEventFired.get(), "发信前必须发布 MailSendingEvent");
        assertNotNull(sentEventHolder.get(), "发信成功后必须发布 MailSentEvent");
        assertEquals(result, sentEventHolder.get().getResult(), "MailSentEvent 回执必须与发信返回结果一致");

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        eventTemplate.destroy();
    }

    @Test
    @DisplayName("测试发信拦截器 MailSendInterceptor 黑名单拦截阻断发信")
    void testMailSendInterceptorBlocked() {
        MailSendInterceptor blacklistInterceptor = message -> {
            if (message.getTo() != null && message.getTo().contains("blocked@blacklist.com")) {
                return false;
            }
            return true;
        };

        MailTemplate interceptedTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                null, List.of(), List.of(blacklistInterceptor));

        MailSendException ex = assertThrows(MailSendException.class, () ->
                interceptedTemplate.sendText("blocked@blacklist.com", "违规邮件", "该邮件应被拦截"));

        assertTrue(ex.getMessage().contains("邮件发送被拦截器阻断"));
        assertEquals(0, greenMail.getReceivedMessages().length);

        interceptedTemplate.destroy();
    }

    @Test
    @DisplayName("测试发信拦截器 MailSendInterceptor 动态修改邮件标头并在 GreenMail 中生效")
    void testMailSendInterceptorHeaderModification() throws Exception {
        MailSendInterceptor headerInterceptor = message -> {
            message.header("X-Custom-Audit-Id", "audit-998877");
            return true;
        };

        MailTemplate interceptedTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                null, List.of(), List.of(headerInterceptor));

        MailSendResult result = interceptedTemplate.sendText("header-target@example.com", "标头测试", "包含自定义标头");

        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage msg = greenMail.getReceivedMessages()[0];
        String[] auditHeaders = msg.getHeader("X-Custom-Audit-Id");
        assertNotNull(auditHeaders);
        assertEquals(1, auditHeaders.length);
        assertEquals("audit-998877", auditHeaders[0]);

        interceptedTemplate.destroy();
    }

    @Test
    @DisplayName("测试发信拦截器 MailSendInterceptor 在 sendWithAccount 及 DynamicMailSession 等全渠道均生效")
    void testMailSendInterceptorAcrossAllChannels() {
        MailSendInterceptor blockingInterceptor = message -> {
            if (message.getSubject() != null && message.getSubject().contains("敏感主题")) {
                return false;
            }
            return true;
        };

        MailTemplate interceptedTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                null, List.of(), List.of(blockingInterceptor));

        MailAccount defaultAccount = accountRegistry.getDefault();

        // 1. 测试 sendWithAccount 通道受拦截器防护
        MailMessage msg1 = new MailMessage()
                .to("channel-test1@example.com")
                .subject("敏感主题-测试1")
                .text("内容1");
        MailSendException ex1 = assertThrows(MailSendException.class, () ->
                interceptedTemplate.sendWithAccount(defaultAccount, msg1));
        assertTrue(ex1.getMessage().contains("邮件发送被拦截器阻断"));

        // 2. 测试 DynamicMailSession 通道受拦截器防护
        int smtpPort = greenMail.getSmtp().getPort();
        MailAccount dynamicAccount = MailAccount.builder()
                .name("channel-dyn-account")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .build();
        MailMessage msg2 = new MailMessage()
                .to("channel-test2@example.com")
                .subject("敏感主题-测试2")
                .text("内容2");
        MailSendException ex2 = assertThrows(MailSendException.class, () ->
                interceptedTemplate.forDynamicAccount(dynamicAccount).send(msg2));
        assertTrue(ex2.getMessage().contains("邮件发送被拦截器阻断"));

        assertEquals(0, greenMail.getReceivedMessages().length);
        interceptedTemplate.destroy();
    }

    @Test
    @DisplayName("测试事务感知发信在物理事务回滚时确定性关闭流资源并释放句柄")
    void testSendAfterCommitRolledBackDeterministicResourceCleanup() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream bais = new ByteArrayInputStream("tx attachment content".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        try {
            MailMessage message = new MailMessage()
                    .to("tx-rollback-cleanup@example.com")
                    .subject("事务回滚资源清理测试")
                    .text("当事务回滚时，挂载的流必须被确定性关闭")
                    .attach("tx-file.txt", bais);

            mailTemplate.sendAfterCommit(message);

            // 尚未触发 completion 前流尚未关闭
            assertFalse(closed.get());

            // 模拟 Spring 事务发生异常并回滚
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            // 验证未发出任何邮件，且附件流被确定性释放关闭
            assertEquals(0, greenMail.getReceivedMessages().length);
            assertTrue(closed.get(), "物理事务回滚取消发信时，必须确定性触发 message.close() 释放资源句柄");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试动态发件账号供给 SPI (MailAccountProvider) 懒加载与 LRU 缓存复用")
    void testDynamicMailAccountProviderLazyLoadingAndLruReuse() {
        int smtpPort = greenMail.getSmtp().getPort();
        AtomicInteger providerCallCount = new AtomicInteger(0);

        MailAccountProvider provider = accountName -> {
            providerCallCount.incrementAndGet();
            if ("db-marketing".equals(accountName)) {
                return MailAccount.builder()
                        .name("db-marketing")
                        .host("127.0.0.1")
                        .port(smtpPort)
                        .username("test@atengk.com")
                        .password("password")
                        .ssl(false)
                        .defaultFrom("marketing@atengk.com")
                        .defaultFromName("市场营销中心")
                        .build();
            }
            return null;
        };

        MailTemplate templateWithProvider = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), provider
        );

        // 第一次发送：静态表无此账号，触发 provider 懒加载，并纳入 LRU 缓存
        MailMessage message1 = new MailMessage()
                .account("db-marketing")
                .to("lead1@customer.com")
                .subject("营销活动推介 1")
                .text("欢迎参与阿腾活动");

        MailSendResult result1 = templateWithProvider.send(message1);
        assertNotNull(result1);
        assertEquals(1, providerCallCount.get(), "首次发信必须触发 Provider 懒加载");
        assertEquals(1, accountRegistry.dynamicSize(), "动态账号成功加载后必须被纳入 LRU 缓存");
        assertNotNull(accountRegistry.getDynamic("db-marketing"), "通过 getDynamic 能够直接检索到受管动态账号");

        // 第二次发送：应命中 LRU 缓存，不再穿透调用 Provider
        MailMessage message2 = new MailMessage()
                .account("db-marketing")
                .to("lead2@customer.com")
                .subject("营销活动推介 2")
                .text("欢迎再次参与阿腾活动");

        MailSendResult result2 = templateWithProvider.send(message2);
        assertNotNull(result2);
        assertEquals(1, providerCallCount.get(), "二次发信命中动态 LRU 缓存，严禁重复穿透外部 Provider 查库");
        assertEquals(2, greenMail.getReceivedMessages().length);
    }

    @Test
    @DisplayName("测试显式指定不存在的账号且 Provider 返回 null 时强制快速失败（双轨防张冠李戴防线）")
    void testExplicitAccountNotFoundFailFast() {
        MailAccountProvider provider = accountName -> null; // 模拟外部数据库查无此账号

        MailTemplate templateWithProvider = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), provider
        );

        MailMessage message = new MailMessage()
                .account("non-existent-vip-account")
                .to("client@example.com")
                .subject("机密财报")
                .text("请查收机密附件");

        MailSendException ex = assertThrows(MailSendException.class, () -> templateWithProvider.send(message));
        assertEquals(MailResultCode.MAIL_ACCOUNT_NOT_FOUND, ex.getResultCode(), "显式指定不存在账号必须抛出 MAIL_ACCOUNT_NOT_FOUND");
        assertTrue(ex.getMessage().contains("non-existent-vip-account"));
        assertEquals(0, greenMail.getReceivedMessages().length, "防张冠李戴防线生效：严禁回退错发至全局默认系统邮箱");
    }

    @Test
    @DisplayName("测试显式指定空白账号名同样严格快速失败防错发（双轨防张冠李戴防线）")
    void testExplicitBlankAccountNotFoundFailFast() {
        MailMessage message = new MailMessage()
                .account("   ")
                .to("client@example.com")
                .subject("机密财报")
                .text("测试空白显式账号");

        MailSendException ex = assertThrows(MailSendException.class, () -> mailTemplate.send(message));
        assertEquals(MailResultCode.MAIL_ACCOUNT_NOT_FOUND, ex.getResultCode(), "显式指定空白账号同样必须抛出 MAIL_ACCOUNT_NOT_FOUND");
        assertEquals(0, greenMail.getReceivedMessages().length, "严禁将显式空白账号穿透回退至默认系统邮箱");
    }

    @Test
    @DisplayName("测试隐式多租户推断账号不存在且 Provider 返回 null 时平滑降级至默认账号")
    void testImplicitTenantAccountNotFoundGracefulFallback() throws Exception {
        TenantContextHolder.setTenantId("unconfigured-tenant");
        MailAccountProvider provider = accountName -> null; // 外部 Provider 亦无此租户账号

        MailTemplate templateWithProvider = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), provider
        );

        try {
            MailMessage message = new MailMessage()
                    // 未显式指定 account，由 TenantMailAccountResolver 隐式推断为 unconfigured-tenant
                    .to("tenant-user@example.com")
                    .subject("租户通知")
                    .text("租户降级发信测试");

            MailSendResult result = templateWithProvider.send(message);
            assertNotNull(result);
            assertEquals(1, greenMail.getReceivedMessages().length, "隐式推断未命中时应平滑降级至默认账号发信成功");
            MimeMessage received = greenMail.getReceivedMessages()[0];
            jakarta.mail.internet.InternetAddress from = (jakarta.mail.internet.InternetAddress) received.getFrom()[0];
            assertEquals("test@atengk.com", from.getAddress());
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    @DisplayName("测试在复用物理 Sender 前提下依据 message.from/fromName 动态覆盖发件人别名")
    void testDynamicSenderAliasOverride() throws Exception {
        MailMessage message = new MailMessage()
                .to("alias-test@example.com")
                .subject("别名动态覆盖测试")
                .text("测试复用连接池时覆盖 From 身份")
                .from("custom-service@atengk.com")
                .fromName("阿腾特服专家");

        MailSendResult result = mailTemplate.send(message);
        assertNotNull(result);

        assertEquals(1, greenMail.getReceivedMessages().length);
        MimeMessage received = greenMail.getReceivedMessages()[0];
        jakarta.mail.Address[] fromAddresses = received.getFrom();
        assertNotNull(fromAddresses);
        assertEquals(1, fromAddresses.length);

        jakarta.mail.internet.InternetAddress from = (jakarta.mail.internet.InternetAddress) fromAddresses[0];
        assertEquals("custom-service@atengk.com", from.getAddress(), "发信地址必须与 message.from 覆盖值一致");
        assertEquals("阿腾特服专家", from.getPersonal(), "发信中文昵称必须与 message.fromName 覆盖值一致");
    }

    @Test
    @DisplayName("测试通过 MailTemplate 门面 API (evictDynamicAccount/clearDynamicAccounts) 精准注销动态账号并重新触发懒加载")
    void testEvictDynamicAccountViaFacadeAndReload() {
        int smtpPort = greenMail.getSmtp().getPort();
        AtomicInteger providerCallCount = new AtomicInteger(0);

        MailAccountProvider provider = accountName -> {
            providerCallCount.incrementAndGet();
            if ("db-sales".equals(accountName)) {
                return MailAccount.builder()
                        .name("db-sales")
                        .host("127.0.0.1")
                        .port(smtpPort)
                        .username("test@atengk.com")
                        .password("password")
                        .ssl(false)
                        .defaultFrom("sales@atengk.com")
                        .defaultFromName("销售业务中心")
                        .build();
            }
            return null;
        };

        MailTemplate template = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), provider
        );

        MailMessage message = new MailMessage()
                .account("db-sales")
                .to("lead@customer.com")
                .subject("商机推介")
                .text("欢迎咨询销售业务");

        // 1. 首次发信，触发 provider 懒加载
        template.send(message);
        assertEquals(1, providerCallCount.get());
        assertEquals(1, accountRegistry.dynamicSize());
        assertNotNull(accountRegistry.getDynamic("db-sales"));

        // 2. 调用门面精准驱逐动态账号
        MailAccount evicted = template.evictDynamicAccount("db-sales");
        assertNotNull(evicted);
        assertEquals("db-sales", evicted.getName());
        assertNull(accountRegistry.getDynamic("db-sales"));

        // 3. 再次发信，因缓存已驱逐，必须重新触发 Provider 懒加载
        template.send(message);
        assertEquals(2, providerCallCount.get(), "精准驱逐后再次发信必须重新触发 Provider 懒加载");
        assertEquals(1, accountRegistry.dynamicSize());

        // 4. 调用门面全量清理动态账号
        template.clearDynamicAccounts();
        assertEquals(0, accountRegistry.dynamicSize());
        assertNull(accountRegistry.getDynamic("db-sales"));

        // 5. 再次发信，再次触发懒加载
        template.send(message);
        assertEquals(3, providerCallCount.get(), "全量清空后再次发信必须重新触发 Provider 懒加载");
    }

    @Test
    @DisplayName("测试通过 Spring 原生领域事件 (MailAccountEvictEvent) 广播驱动动态账号热驱逐")
    void testEvictDynamicAccountViaEventBroadcast() {
        int smtpPort = greenMail.getSmtp().getPort();
        AtomicInteger providerCallCount = new AtomicInteger(0);

        MailAccountProvider provider = accountName -> {
            providerCallCount.incrementAndGet();
            if ("db-vip".equals(accountName)) {
                return MailAccount.builder()
                        .name("db-vip")
                        .host("127.0.0.1")
                        .port(smtpPort)
                        .username("test@atengk.com")
                        .password("password")
                        .ssl(false)
                        .defaultFrom("vip@atengk.com")
                        .defaultFromName("VIP贵宾服务")
                        .build();
            }
            return null;
        };

        MailAccountEvictListener evictListener = new MailAccountEvictListener(accountRegistry);
        MailTemplate template = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), provider
        );

        MailMessage message = new MailMessage()
                .account("db-vip")
                .to("vip@customer.com")
                .subject("贵宾私享会")
                .text("欢迎参加贵宾私享会");

        // 1. 首次发信，加载进动态缓存
        template.send(message);
        assertEquals(1, providerCallCount.get());
        assertEquals(1, accountRegistry.dynamicSize());

        // 2. 模拟分布式集群或本地事件总线广播精准驱逐事件
        evictListener.onMailAccountEvict(new MailAccountEvictEvent(this, "db-vip"));
        assertNull(accountRegistry.getDynamic("db-vip"));

        // 3. 再次发信，验证重新触发懒加载
        template.send(message);
        assertEquals(2, providerCallCount.get(), "收到单账号驱逐事件后再次发信必须重新触发 Provider 懒加载");

        // 4. 广播全量清空事件
        evictListener.onMailAccountEvict(new MailAccountEvictEvent(this, null));
        assertEquals(0, accountRegistry.dynamicSize());

        // 5. 再次发信，验证重新触发懒加载
        template.send(message);
        assertEquals(3, providerCallCount.get(), "收到全量清空事件后再次发信必须重新触发 Provider 懒加载");
    }

    @Test
    @DisplayName("测试批量个性化发信全量成功 (sendBatch)")
    void testSendBatchSuccess() {
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            messages.add(new MailMessage()
                    .to("user" + i + "@atengk.com")
                    .subject("个性化工资条 第" + i + "期")
                    .text("尊敬的员工" + i + "，您的实发薪资已到账。"));
        }

        BatchMailSendResult batchResult = mailTemplate.sendBatch(messages);

        assertNotNull(batchResult);
        assertEquals(5, batchResult.getTotalCount());
        assertEquals(5, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailureCount());
        assertTrue(batchResult.isAllSuccess());
        assertFalse(batchResult.hasFailure());
        assertEquals(5, batchResult.getItems().size());

        for (int i = 0; i < 5; i++) {
            MailSendResultItem item = batchResult.getItems().get(i);
            assertEquals(i, item.getIndex());
            assertTrue(item.isSuccess());
            assertNotNull(item.getResult());
            assertNotNull(item.getResult().getMessageId());
            assertEquals(List.of("user" + (i + 1) + "@atengk.com"), item.getRecipients());
        }

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertTrue(received.length >= 5, "GreenMail 应收到全部 5 封邮件");
    }

    @Test
    @DisplayName("测试批量个性化发信 Fail-Safe 容错与聚合统计")
    void testSendBatchPartialFailureFailSafe() {
        MailMessage msg1 = new MailMessage()
                .to("valid1@atengk.com")
                .subject("正常通知1")
                .text("内容1");
        // 制造收件人为空的异常邮件
        MailMessage msg2 = new MailMessage()
                .subject("异常邮件")
                .text("无收件人");
        MailMessage msg3 = new MailMessage()
                .to("valid2@atengk.com")
                .subject("正常通知2")
                .text("内容2");

        BatchMailSendResult batchResult = mailTemplate.sendBatch(List.of(msg1, msg2, msg3));

        assertNotNull(batchResult);
        assertEquals(3, batchResult.getTotalCount());
        assertEquals(2, batchResult.getSuccessCount());
        assertEquals(1, batchResult.getFailureCount());
        assertFalse(batchResult.isAllSuccess());
        assertTrue(batchResult.hasFailure());

        assertEquals(2, batchResult.getSuccessItems().size());
        assertEquals(1, batchResult.getFailureItems().size());

        MailSendResultItem failureItem = batchResult.getFailureItems().get(0);
        assertEquals(1, failureItem.getIndex());
        assertFalse(failureItem.isSuccess());
        assertNotNull(failureItem.getErrorMessage());
        assertNotNull(failureItem.getCause());
    }

    @Test
    @DisplayName("测试异步批量个性化发信 (sendBatchAsync)")
    void testSendBatchAsync() throws Exception {
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            messages.add(new MailMessage()
                    .to("async" + i + "@atengk.com")
                    .subject("异步批量通知 第" + i + "期")
                    .text("异步通知内容" + i));
        }

        CompletableFuture<BatchMailSendResult> future = mailTemplate.sendBatchAsync(messages);
        BatchMailSendResult batchResult = future.get();

        assertNotNull(batchResult);
        assertEquals(3, batchResult.getTotalCount());
        assertEquals(3, batchResult.getSuccessCount());
        assertEquals(0, batchResult.getFailureCount());
        assertTrue(batchResult.isAllSuccess());
    }

    @Test
    @DisplayName("测试安全沙箱白名单放行真实物理发信")
    void testMailSandboxWhitelistDelivery() throws Exception {
        mailProperties.getSandbox().setEnabled(true);
        mailProperties.getSandbox().setAllowedRecipients(List.of("*@atengk.com"));
        mailProperties.getSandbox().setRedirectTo("trap@atengk.com");

        MailSandboxInterceptor sandboxInterceptor = new MailSandboxInterceptor(mailProperties.getSandbox());
        MailTemplate sandboxTemplate = new MailTemplate(
                accountRegistry,
                mailProperties,
                new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(),
                null,
                null,
                null,
                null,
                List.of(sandboxInterceptor)
        );

        try {
            MailMessage message = new MailMessage()
                    .to("valid@atengk.com")
                    .subject("白名单员工通知")
                    .text("欢迎查阅内部通知。");

            MailSendResult result = sandboxTemplate.send(message);

            assertNotNull(result);
            assertTrue(result.isSuccess());

            // GreenMail 真实物理接收到该邮件
            jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
            assertEquals(1, received.length);
            assertEquals("白名单员工通知", received[0].getSubject());
            assertEquals("valid@atengk.com", received[0].getAllRecipients()[0].toString());
        } finally {
            sandboxTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试安全沙箱重定向模式改写 To 并清空 CC/BCC")
    void testMailSandboxRedirectDelivery() throws Exception {
        mailProperties.getSandbox().setEnabled(true);
        mailProperties.getSandbox().setAllowedRecipients(List.of("*@internal.corp"));
        mailProperties.getSandbox().setRedirectTo("trap@atengk.com");

        MailSandboxInterceptor sandboxInterceptor = new MailSandboxInterceptor(mailProperties.getSandbox());
        MailTemplate sandboxTemplate = new MailTemplate(
                accountRegistry,
                mailProperties,
                new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(),
                null,
                null,
                null,
                null,
                List.of(sandboxInterceptor)
        );

        try {
            MailMessage message = new MailMessage()
                    .to("real-customer@gmail.com")
                    .cc("partner@qq.com")
                    .bcc("secret-audit@external.com")
                    .subject("生产敏感业务账单")
                    .text("账单详情...");

            MailSendResult result = sandboxTemplate.send(message);

            assertNotNull(result);
            assertTrue(result.isSuccess());

            // 物理发信投递且仅投递至重定向安全邮箱
            jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
            assertEquals(1, received.length);
            // 主题自动注入重定向追踪标头
            assertEquals("[SANDBOX-REDIRECT to: real-customer@gmail.com] 生产敏感业务账单", received[0].getSubject());
            // 物理收件人仅有 trap@atengk.com
            assertEquals(1, received[0].getAllRecipients().length);
            assertEquals("trap@atengk.com", received[0].getAllRecipients()[0].toString());
            // 原 CC 与 BCC 不在物理邮件标头中
            assertNull(received[0].getRecipients(jakarta.mail.Message.RecipientType.CC));
            assertNull(received[0].getRecipients(jakarta.mail.Message.RecipientType.BCC));
        } finally {
            sandboxTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试安全沙箱纯日志模式 (log-only=true) 零物理连接且返回合规虚拟回执")
    void testMailSandboxLogOnlyDelivery() {
        mailProperties.getSandbox().setEnabled(true);
        mailProperties.getSandbox().setLogOnly(true);

        MailSandboxInterceptor sandboxInterceptor = new MailSandboxInterceptor(mailProperties.getSandbox());
        MailTemplate sandboxTemplate = new MailTemplate(
                accountRegistry,
                mailProperties,
                new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(),
                null,
                null,
                null,
                null,
                List.of(sandboxInterceptor)
        );

        try {
            MailMessage message = new MailMessage()
                    .to("simulated-user@external.com")
                    .subject("纯日志模拟发信测试")
                    .text("此邮件不会建立真实物理网络连接");

            MailSendResult result = sandboxTemplate.send(message);

            assertNotNull(result);
            assertTrue(result.isSuccess());
            assertTrue(result.getMessageId().startsWith("<sandbox-log-"),
                    "纯日志模式回执 Message-ID 必须为 <sandbox-log-${traceId}@domain> 格式");

            // 物理 SMTP 零流量
            assertEquals(0, greenMail.getReceivedMessages().length,
                    "纯日志模拟模式下绝不能向真实网络或 GreenMail 发送物理报文");
        } finally {
            sandboxTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试显式传入 Locale 进行国际化模板渲染发信")
    void testSendTemplateWithExplicitLocale() throws Exception {
        MailSendResult result = mailTemplate.sendTemplate(
                "locale-user@atengk.com",
                "国际化多语言邮件",
                "welcome",
                Map.of("username", "Emma", "platform", "Ateng Cloud"),
                Locale.US
        );

        assertNotNull(result);
        assertTrue(result.isSuccess());

        jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        String content = GreenMailUtil.getBody(received[0]);
        assertTrue(content.contains("Welcome US, Emma!"));
        assertTrue(content.contains("Thank you for joining Ateng Cloud in US!"));
    }

    @Test
    @DisplayName("测试自动探测 LocaleContextHolder 国际化语言环境渲染发信")
    void testSendTemplateWithLocaleContextHolder() throws Exception {
        LocaleContextHolder.setLocale(Locale.UK);
        try {
            MailSendResult result = mailTemplate.sendTemplate(
                    "locale-uk@atengk.com",
                    "UK通知",
                    "welcome",
                    Map.of("username", "Oliver", "platform", "Ateng Cloud")
            );

            assertNotNull(result);
            assertTrue(result.isSuccess());

            jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
            assertEquals(1, received.length);
            String content = GreenMailUtil.getBody(received[0]);
            // 级联降级匹配至 welcome_en.html
            assertTrue(content.contains("Welcome EN, Oliver!"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("测试物理连通性健康探测 testConnection 与 testConnectionOrThrow 成功")
    void testTestConnectionSuccess() {
        // 1. 默认账号探测
        assertTrue(mailTemplate.testConnection());
        assertDoesNotThrow(() -> mailTemplate.testConnectionOrThrow());

        // 2. 指定账号名探测
        assertTrue(mailTemplate.testConnection("ops"));
        assertDoesNotThrow(() -> mailTemplate.testConnectionOrThrow("ops"));

        // 3. Registry 层级探测
        assertTrue(accountRegistry.testConnection("default"));
        assertDoesNotThrow(() -> accountRegistry.testConnectionOrThrow("default"));

        // 验证探针为纯网络握手与认证，零发信副作用
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    @Test
    @DisplayName("测试物理连通性探针在认证失败时返回 false 并抛出 MAIL_AUTHENTICATION_ERROR")
    void testTestConnectionAuthFailure() {
        int smtpPort = greenMail.getSmtp().getPort();

        // 注册密码错误的发件账号
        JavaMailSenderImpl badSender = new JavaMailSenderImpl();
        badSender.setHost("127.0.0.1");
        badSender.setPort(smtpPort);
        badSender.setUsername("test@atengk.com");
        badSender.setPassword("invalid-password-12345");
        badSender.getJavaMailProperties().put("mail.smtp.auth", "true");

        MailAccount badAccount = MailAccount.builder()
                .name("bad-auth")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("invalid-password-12345")
                .sender(badSender)
                .build();
        accountRegistry.register(badAccount);

        // 快速布尔型探测返回 false
        assertFalse(mailTemplate.testConnection("bad-auth"));

        // 异常型探测抛出 MAIL_AUTHENTICATION_ERROR
        MailSendException ex = assertThrows(MailSendException.class, () -> mailTemplate.testConnectionOrThrow("bad-auth"));
        assertEquals(MailResultCode.MAIL_AUTHENTICATION_ERROR, ex.getResultCode());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("测试物理连通性探针在账号不存在时抛出 MAIL_ACCOUNT_NOT_FOUND")
    void testTestConnectionAccountNotFound() {
        assertFalse(mailTemplate.testConnection("non-existent-account-xyz"));
        MailSendException ex = assertThrows(MailSendException.class, () -> mailTemplate.testConnectionOrThrow("non-existent-account-xyz"));
        assertEquals(MailResultCode.MAIL_ACCOUNT_NOT_FOUND, ex.getResultCode());
    }

    @Test
    @DisplayName("测试 DynamicMailSession 门面调用 testConnection 探针")
    void testDynamicMailSessionTestConnection() {
        MailAccount defaultAcc = accountRegistry.getDefault();
        DynamicMailSession session = mailTemplate.forDynamicAccount(defaultAcc);

        assertTrue(session.testConnection());
        assertDoesNotThrow(session::testConnectionOrThrow);
    }

    // ==================== 合规密送底单 (archive-bcc) 与强类型邮件优先级标头测试 ====================

    @Test
    @DisplayName("测试全局配置 archive-bcc 自动为外发邮件追加合规密送")
    void testArchiveBccAppendedAutomatically() throws Exception {
        mailProperties.setArchiveBcc("compliance@atengk.com");

        MailMessage msg = new MailMessage()
                .to("client-user@example.com")
                .subject("合规归档密送测试")
                .text("正文内容...");

        MailSendResult result = mailTemplate.send(msg);

        assertTrue(result.isSuccess());
        // 发送给 1 个 To + 1 个 Bcc，共投递 2 份副本
        assertTrue(greenMail.waitForIncomingEmail(5000, 2));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(2, messages.length);
        assertEquals("合规归档密送测试", messages[0].getSubject());

        assertTrue(msg.getBcc().contains("compliance@atengk.com"));
    }

    @Test
    @DisplayName("测试 archive-bcc 与业务显式 Bcc 大小写无关去重合并")
    void testArchiveBccDeduplicationCaseInsensitive() throws Exception {
        // 全局配置大写的归档邮箱以及额外的审计邮箱
        mailProperties.setArchiveBcc("COMPLIANCE@atengk.com, audit@atengk.com");

        MailMessage msg = new MailMessage()
                .to("target@example.com")
                .bcc("compliance@atengk.com", "other-bcc@example.com")
                .subject("去重合并密送测试")
                .text("验证重复与大小写不会产生冗余收件人");

        MailSendResult result = mailTemplate.send(msg);

        assertTrue(result.isSuccess());
        // 1 To + 3 去重后的 Bcc (compliance@atengk.com, other-bcc@example.com, audit@atengk.com) = 4 份副本
        assertTrue(greenMail.waitForIncomingEmail(5000, 4));

        assertEquals(3, msg.getBcc().size());
        assertTrue(msg.getBcc().contains("compliance@atengk.com"));
        assertTrue(msg.getBcc().contains("other-bcc@example.com"));
        assertTrue(msg.getBcc().contains("audit@atengk.com"));
    }

    @Test
    @DisplayName("测试账号级别专职 archive-bcc 覆盖全局默认配置")
    void testAccountSpecificArchiveBccOverride() throws Exception {
        mailProperties.setArchiveBcc("global-archive@atengk.com");

        // 注册独立配有 archiveBcc 的账号
        int smtpPort = greenMail.getSmtp().getPort();
        JavaMailSenderImpl customSender = new JavaMailSenderImpl();
        customSender.setHost("127.0.0.1");
        customSender.setPort(smtpPort);
        customSender.setUsername("test@atengk.com");
        customSender.setPassword("password");

        MailAccount customAccount = MailAccount.builder()
                .name("legal-channel")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .archiveBcc("legal-archive@atengk.com")
                .sender(customSender)
                .build();
        accountRegistry.register(customAccount);

        MailMessage msg = new MailMessage()
                .account("legal-channel")
                .to("external-partner@example.com")
                .subject("法务通道专用归档")
                .text("归档至 legal-archive");

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 2));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(2, messages.length);

        assertTrue(msg.getBcc().contains("legal-archive@atengk.com"));
        assertFalse(msg.getBcc().contains("global-archive@atengk.com"));
    }

    @Test
    @DisplayName("测试 archive-bcc 防御不可变 Bcc 列表：传入 List.of 时安全合并绝不抛出异常")
    void testArchiveBccWithImmutableBccListDoesNotThrow() throws Exception {
        mailProperties.setArchiveBcc("compliance@atengk.com");

        MailMessage msg = new MailMessage()
                .to("target@example.com")
                .subject("不可变密送合并测试")
                .text("测试 List.of 不可变集合");
        // 显式设置不可变 List
        msg.setBcc(List.of("audit@atengk.com"));

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 2));

        assertEquals(2, msg.getBcc().size());
        assertTrue(msg.getBcc().contains("audit@atengk.com"));
        assertTrue(msg.getBcc().contains("compliance@atengk.com"));
    }

    @Test
    @DisplayName("测试 MailPriority.HIGH 高优先级邮件自动注入 X-Priority 与 Importance 标头")
    void testMailPriorityHighHeaders() throws Exception {
        MailMessage msg = new MailMessage()
                .to("urgent-user@example.com")
                .subject("紧急线上故障告警")
                .priority(MailPriority.HIGH)
                .text("请立即处理 P0 故障！");

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertEquals("1", received.getHeader("X-Priority", null));
        assertEquals("High", received.getHeader("Importance", null));
        assertEquals("High", received.getHeader("X-MSMail-Priority", null));
    }

    @Test
    @DisplayName("测试 MailPriority.LOW 低优先级邮件自动注入对应标头")
    void testMailPriorityLowHeaders() throws Exception {
        MailMessage msg = new MailMessage()
                .to("digest-user@example.com")
                .subject("每周技术周刊")
                .priority(MailPriority.LOW)
                .text("每周五轻量推送周报");

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertEquals("5", received.getHeader("X-Priority", null));
        assertEquals("Low", received.getHeader("Importance", null));
        assertEquals("Low", received.getHeader("X-MSMail-Priority", null));
    }

    @Test
    @DisplayName("测试 readReceiptTo 显式指定阅读回执目标邮箱 (Disposition-Notification-To)")
    void testReadReceiptToHeader() throws Exception {
        MailMessage msg = new MailMessage()
                .to("contract-signer@example.com")
                .subject("重要合同盖章通知")
                .readReceiptTo("receipt-inbox@atengk.com")
                .text("请查收并确认阅读合同附件");

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertEquals("receipt-inbox@atengk.com", received.getHeader("Disposition-Notification-To", null));
    }

    @Test
    @DisplayName("测试 requestReadReceipt(true) 未显式指定回执邮箱时自动使用有效发件人地址")
    void testRequestReadReceiptFallbackToFrom() throws Exception {
        MailMessage msg = new MailMessage()
                .to("employee@example.com")
                .subject("员工考勤确认函")
                .requestReadReceipt(true)
                .text("请阅读后点击确认回执");

        MailSendResult result = mailTemplate.send(msg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        // 有效发件人地址为 defaultFrom: test@atengk.com
        assertEquals("test@atengk.com", received.getHeader("Disposition-Notification-To", null));
    }

    @Test
    @DisplayName("测试 sendAfterCommit 收尾严格调用 previous.restore() 对称还原扩展上下文")
    void testAfterCommitRestoresPreviousExtensionContextSafely() {
        ThreadLocal<String> extHolder = new ThreadLocal<>();
        AtomicBoolean providerActive = new AtomicBoolean(true);
        List<Object> appliedSnapshots = new ArrayList<>();
        List<Object> restoredSnapshots = new ArrayList<>();

        AsyncContextSnapshotProvider testProvider = new AsyncContextSnapshotProvider() {
            @Override
            public Object capture() {
                return providerActive.get() ? extHolder.get() : null;
            }

            @Override
            public void apply(Object snapshot) {
                if (!providerActive.get()) {
                    return;
                }
                appliedSnapshots.add(snapshot);
                if (snapshot instanceof String str) {
                    extHolder.set(str);
                } else {
                    extHolder.remove();
                }
            }

            @Override
            public void restore(Object previous) {
                if (!providerActive.get()) {
                    return;
                }
                restoredSnapshots.add(previous);
                if (previous instanceof String str) {
                    extHolder.set(str);
                } else {
                    extHolder.remove();
                }
            }
        };
        AsyncContext.registerSnapshotProvider(testProvider);

        try {
            extHolder.set("tx-snapshot");
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            mailTemplate.sendAfterCommit(msg -> msg
                    .to("tx-restore@example.com")
                    .subject("上下文还原验证")
                    .text("hello"));

            extHolder.set("outer-prev");
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            assertEquals(List.of("tx-snapshot"), appliedSnapshots, "apply 只应在切入事务快照时触发");
            assertEquals(List.of("outer-prev"), restoredSnapshots, "finally 必须对称调用 restore 还原先前上下文");
            assertEquals("outer-prev", extHolder.get());
        } finally {
            providerActive.set(false);
            AsyncContext.unregisterSnapshotProvider(testProvider);
            extHolder.remove();
        }
    }

    @Test
    @DisplayName("测试 sendAfterCommit 前置校验失败（如账号未找到）时触发一次安全网事件、回调且关闭流资源")
    void testAfterCommitPreValidationFailureTriggersSafetyNetOnce() {
        AtomicInteger handlerCount = new AtomicInteger(0);
        AtomicReference<Throwable> capturedCause = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        MailSendFailureHandler failureHandler = (msg, cause) -> {
            handlerCount.incrementAndGet();
            capturedCause.set(cause);
        };
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof MailSendFailedEvent) {
                eventCount.incrementAndGet();
            }
        };

        MailTemplate customTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                publisher, List.of(failureHandler));

        ByteArrayInputStream bais = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };
        MailMessage invalidMsg = new MailMessage()
                .account("missing-tx-account")
                .to("tx-safety@example.com")
                .subject("事务前置失败安全网测试")
                .text("body")
                .attach("tx.txt", bais);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            customTemplate.sendAfterCommit(invalidMsg);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } finally {
            customTemplate.destroy();
        }

        assertEquals(1, handlerCount.get(), "事务前置失败同样必须触发且仅触发一次 MailSendFailureHandler");
        assertEquals(1, eventCount.get(), "事务前置失败同样必须发布且仅发布一次 MailSendFailedEvent");
        assertInstanceOf(MailSendException.class, capturedCause.get());
        assertEquals(MailResultCode.MAIL_ACCOUNT_NOT_FOUND.getCode(), ((MailSendException) capturedCause.get()).getCode());
        assertTrue(streamClosed.get(), "事务前置失败后消息资源句柄必须被确定性关闭");
    }

    @Test
    @DisplayName("测试 sendAfterCommit 物理发送失败时安全网与失败事件仅触发一次（无重复触发）")
    void testAfterCommitDeliveryFailureTriggersSafetyNetOnlyOnce() {
        AtomicInteger handlerCount = new AtomicInteger(0);
        AtomicInteger eventCount = new AtomicInteger(0);

        MailSendFailureHandler failureHandler = (msg, cause) -> handlerCount.incrementAndGet();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof MailSendFailedEvent) {
                eventCount.incrementAndGet();
            }
        };

        MailTemplate customTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                mailTemplate.getAccountResolver(), mailTemplate.getExecutor(),
                publisher, List.of(failureHandler));

        MailAccount deadAccount = MailAccount.builder()
                .name("tx-dead")
                .host("127.0.0.1")
                .port(59998)
                .ssl(false)
                .sender(new JavaMailSenderImpl() {
                    @Override
                    public void send(MimeMessage mimeMessage) {
                        throw new org.springframework.mail.MailSendException("SMTP unreachable in tx");
                    }
                })
                .build();
        accountRegistry.register(deadAccount);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            customTemplate.sendAfterCommit(msg -> msg
                    .account("tx-dead")
                    .to("tx-dead@example.com")
                    .subject("物理发送失败一次安全网测试")
                    .text("body"));

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            customTemplate.destroy();
        }

        assertEquals(1, handlerCount.get());
        assertEquals(1, eventCount.get());
    }

    @Test
    @DisplayName("测试严格邮件地址格式校验：拦截 null/空白元素与非法格式且不触发重试，兼容显示名称邮箱")
    void testStrictEmailAddressValidationAndDisplayNameSupport() throws Exception {
        mailProperties.getRetry().setEnabled(true);
        mailProperties.getRetry().setMaxAttempts(3);
        mailProperties.getRetry().setDelay(Duration.ofMillis(10));

        // 1. to 全为空白时抛出 MAIL_RECIPIENT_EMPTY
        MailSendException allBlankTo = assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage().to("   ").subject("S").text("T")));
        assertEquals(MailResultCode.MAIL_RECIPIENT_EMPTY, allBlankTo.getResultCode());

        // 2. to / cc / bcc 混入 null 或空白元素时抛出 MAIL_INVALID_ADDRESS
        MailSendException mixedNullTo = assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage().to("valid@example.com", null).subject("S").text("T")));
        assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, mixedNullTo.getResultCode());

        MailSendException blankCc = assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage().to("valid@example.com").cc("  ").subject("S").text("T")));
        assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, blankCc.getResultCode());

        MailSendException nullBcc = assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage().to("valid@example.com").bcc((String) null).subject("S").text("T")));
        assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, nullBcc.getResultCode());

        // 3. 非法格式（含缺少@、后缀残缺、多地址注入、CRLF注入）在全字段均抛出 MAIL_INVALID_ADDRESS 且不触发重试
        AtomicInteger attempts = new AtomicInteger(0);
        MailAccount countingAccount = MailAccount.builder()
                .name("addr-check-acc")
                .host("127.0.0.1")
                .port(greenMail.getSmtp().getPort())
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .sender(new JavaMailSenderImpl() {
                    @Override
                    public void send(MimeMessage mimeMessage) {
                        attempts.incrementAndGet();
                        super.send(mimeMessage);
                    }
                })
                .build();
        accountRegistry.register(countingAccount);

        for (MailMessage invalidMsg : List.of(
                new MailMessage().account("addr-check-acc").to("not-an-email").subject("S").text("T"),
                new MailMessage().account("addr-check-acc").to("valid@example.com").cc("bad@").subject("S").text("T"),
                new MailMessage().account("addr-check-acc").to("valid@example.com").bcc("a@b.com, c@d.com").subject("S").text("T"),
                new MailMessage().account("addr-check-acc").to("valid@example.com").from("invalid-from").subject("S").text("T"),
                new MailMessage().account("addr-check-acc").to("valid@example.com").replyTo("bad\r\n@example.com").subject("S").text("T"),
                new MailMessage().account("addr-check-acc").to("valid@example.com").readReceiptTo("bad-receipt").subject("S").text("T")
        )) {
            MailSendException ex = assertThrows(MailSendException.class, () -> mailTemplate.send(invalidMsg));
            assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, ex.getResultCode());
        }
        assertEquals(0, attempts.get(), "地址校验失败必须立即 Fail-Fast，严禁进入物理发送或重试");

        // 4. archiveBcc 非法地址同样抛出 MAIL_INVALID_ADDRESS
        mailProperties.setArchiveBcc("compliance@atengk.com, invalid-archive-addr");
        MailSendException archiveEx = assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage().to("valid@example.com").subject("S").text("T")));
        assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, archiveEx.getResultCode());
        mailProperties.setArchiveBcc(null);

        // 5. 合法显示名称格式（如 "张三 <zhangsan@example.com>"）正常发信成功
        MailSendResult validResult = mailTemplate.send(msg -> msg
                .from("客服中心 <support@atengk.com>")
                .to("张三 <zhangsan@example.com>")
                .replyTo("工单系统 <reply@atengk.com>")
                .subject("显示名称格式校验")
                .text("兼容性验证"));
        assertTrue(validResult.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
    }

    @Test
    @DisplayName("测试发送前清洗 Builder/Setter 构造的 CRLF 头部注入并验证 GreenMail 无伪造标头")
    void testCrlfHeaderInjectionBlockedInGreenMail() throws Exception {
        io.github.atengk.boot.mail.model.MailInlineResource dirtyInline =
                io.github.atengk.boot.mail.model.MailInlineResource.builder()
                        .contentId("cid1\r\nX-Injected-Cid: evil")
                        .source(new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}))
                        .contentType("image/png")
                        .size(3L)
                        .build();

        MailMessage dirtyMsg = MailMessage.builder()
                .to(List.of("crlf-check@example.com"))
                .from("sender@atengk.com")
                .fromName("客服\r\nX-Injected-From: evil")
                .subject("订单通知\r\nX-Injected-Subj: evil")
                .html(true)
                .text("<img src='cid:cid1'/>")
                .headers(Map.of("X-Safe\r\n", "ok\r\nX-Injected-Hdr: evil"))
                .inlines(List.of(dirtyInline))
                .build();

        MailSendResult result = mailTemplate.send(dirtyMsg);
        assertTrue(result.isSuccess());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertNull(received.getHeader("X-Injected-Subj"));
        assertNull(received.getHeader("X-Injected-From"));
        assertNull(received.getHeader("X-Injected-Hdr"));
        assertNull(received.getHeader("X-Injected-Cid"));
        assertFalse(received.getSubject().contains("\r"));
        assertFalse(received.getSubject().contains("\n"));
    }

    @Test
    @DisplayName("测试自动跳过系统垃圾文件附件与内联资源且确定性关流")
    void testSkipSystemJunkAttachmentsAndInlines(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path dsStore = tempDir.resolve(".DS_Store");
        java.nio.file.Files.writeString(dsStore, "mac metadata");

        AtomicBoolean junkStreamClosed = new AtomicBoolean(false);
        ByteArrayInputStream junkStream = new ByteArrayInputStream("win metadata".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                junkStreamClosed.set(true);
                super.close();
            }
        };

        java.nio.file.Path thumbsDb = tempDir.resolve("thumbs.db");
        java.nio.file.Files.writeString(thumbsDb, "thumb");

        MailSendResult result = mailTemplate.send(msg -> msg
                .to("junk-filter@example.com")
                .subject("垃圾文件过滤测试")
                .html("<p>正文</p>")
                .attach(io.github.atengk.boot.mail.model.MailAttachment.of(dsStore.toFile()))
                .attach("desktop.ini", junkStream)
                .inline("junkCid", thumbsDb.toFile())
                .attach("real-report.txt", "valid-data".getBytes(StandardCharsets.UTF_8)));

        assertTrue(result.isSuccess());
        assertTrue(junkStreamClosed.get(), "被跳过的系统垃圾文件流仍须确定性关闭");
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));

        MimeMessage received = greenMail.getReceivedMessages()[0];
        List<String> attachedNames = new ArrayList<>();
        collectAttachmentFilenames(received, attachedNames);
        assertEquals(List.of("real-report.txt"), attachedNames);
    }

    @Test
    @DisplayName("测试裸一次性 InputStream 禁用重试防空文件损坏，而 ofBuffered 与 byte[] 正常重试恢复")
    void testNonRepeatableStreamDisablesRetryAndOfBufferedRetries() throws Exception {
        mailProperties.getRetry().setEnabled(true);
        mailProperties.getRetry().setMaxAttempts(3);
        mailProperties.getRetry().setDelay(Duration.ofMillis(10));
        mailProperties.getRetry().setJitter(false);

        int smtpPort = greenMail.getSmtp().getPort();
        JavaMailSenderImpl realSender = new JavaMailSenderImpl();
        realSender.setHost("127.0.0.1");
        realSender.setPort(smtpPort);
        realSender.setUsername("test@atengk.com");
        realSender.setPassword("password");

        AtomicInteger attemptCounter = new AtomicInteger(0);
        MailAccount flakyAccount = MailAccount.builder()
                .name("flaky-stream-acc")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("test@atengk.com")
                .sender(new JavaMailSenderImpl() {
                    @Override
                    public void send(MimeMessage mimeMessage) {
                        if (attemptCounter.incrementAndGet() == 1) {
                            throw new org.springframework.mail.MailSendException("transient glitch");
                        }
                        realSender.send(mimeMessage);
                    }
                })
                .build();
        accountRegistry.register(flakyAccount);

        // 1. 裸一次性 InputStream 禁用重试：只执行 1 次并直接失败，同时关闭流
        AtomicBoolean rawClosed = new AtomicBoolean(false);
        ByteArrayInputStream rawStream = new ByteArrayInputStream("once-only-payload".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                rawClosed.set(true);
                super.close();
            }
        };
        assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .account("flaky-stream-acc")
                        .to("once@example.com")
                        .subject("裸流禁重试")
                        .text("body")
                        .attach("once.txt", rawStream)));
        assertEquals(1, attemptCounter.get(), "裸 InputStream 附件开启重试时必须禁用重试，严禁二次投递");
        assertTrue(rawClosed.get());
        assertEquals(0, greenMail.getReceivedMessages().length);

        // 2. ofBuffered 有界缓冲附件支持正常重试并完整投递
        attemptCounter.set(0);
        ByteArrayInputStream bufSource = new ByteArrayInputStream("buffered-retry-payload".getBytes(StandardCharsets.UTF_8));
        MailAttachment bufferedAttachment = MailAttachment.ofBuffered("buffered.txt", bufSource, 1024);
        MailSendResult bufferedResult = mailTemplate.send(msg -> msg
                .account("flaky-stream-acc")
                .to("buffered@example.com")
                .subject("缓冲流重试恢复")
                .text("body")
                .attach(bufferedAttachment));
        assertTrue(bufferedResult.isSuccess());
        assertEquals(2, attemptCounter.get(), "ofBuffered 资源应正常重试并于第 2 次恢复");
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        Multipart mp = (Multipart) received.getContent();
        String attachedContent = new String(mp.getBodyPart(1).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("buffered-retry-payload", attachedContent);
    }

    @Test
    @DisplayName("测试前置校验异常、拦截器阻断与异步线程池拒绝全路径均确定性关闭 MailMessage 流资源")
    void testFullPathDeterministicCloseOnValidationInterceptorAndExecutorRejection() {
        // 1. 前置校验失败（MAIL_ACCOUNT_NOT_FOUND / MAIL_INVALID_ADDRESS / MAIL_RECIPIENT_EMPTY）
        AtomicBoolean closed1 = new AtomicBoolean(false);
        assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage()
                        .account("missing-acc")
                        .to("a@example.com")
                        .subject("s")
                        .text("t")
                        .attach("a.txt", trackingStream(closed1))));
        assertTrue(closed1.get(), "账号不存在前置失败时必须关闭附件流");

        AtomicBoolean closed2 = new AtomicBoolean(false);
        assertThrows(MailSendException.class, () ->
                mailTemplate.send(new MailMessage()
                        .to("bad-email")
                        .subject("s")
                        .text("t")
                        .attach("b.txt", trackingStream(closed2))));
        assertTrue(closed2.get(), "地址非法前置失败时必须关闭附件流");

        // 2. 拦截器阻断
        AtomicBoolean closed3 = new AtomicBoolean(false);
        MailTemplate blockedTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null,
                List.of(msg -> false));
        try {
            assertThrows(MailSendException.class, () ->
                    blockedTemplate.send(new MailMessage()
                            .to("c@example.com")
                            .subject("s")
                            .text("t")
                            .attach("c.txt", trackingStream(closed3))));
            assertTrue(closed3.get(), "拦截器阻断时必须关闭附件流");
        } finally {
            blockedTemplate.destroy();
        }

        // 3. 异步线程池拒绝提交 (sendAsync, sendBatch, sendBatchAsync)
        java.util.concurrent.Executor rejectingExecutor = cmd -> {
            throw new java.util.concurrent.RejectedExecutionException("pool saturated");
        };
        MailTemplate rejectedTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), rejectingExecutor);

        AtomicBoolean closed4 = new AtomicBoolean(false);
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () ->
                rejectedTemplate.sendAsync(new MailMessage()
                        .to("d@example.com")
                        .subject("s")
                        .text("t")
                        .attach("d.txt", trackingStream(closed4))));
        assertTrue(closed4.get(), "sendAsync 线程池拒绝提交时必须关闭附件流");

        AtomicBoolean closed5 = new AtomicBoolean(false);
        BatchMailSendResult batchResult = rejectedTemplate.sendBatch(List.of(new MailMessage()
                .to("e@example.com")
                .subject("s")
                .text("t")
                .attach("e.txt", trackingStream(closed5))));
        assertEquals(1, batchResult.getFailureCount());
        assertTrue(closed5.get(), "sendBatch 线程池拒绝提交时必须关闭附件流");

        AtomicBoolean closed6 = new AtomicBoolean(false);
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () ->
                rejectedTemplate.sendBatchAsync(List.of(new MailMessage()
                        .to("f@example.com")
                        .subject("s")
                        .text("t")
                        .attach("f.txt", trackingStream(closed6)))));
        assertTrue(closed6.get(), "sendBatchAsync 线程池拒绝提交时必须关闭附件流");

        // 4. async.enabled=false (executor == null) 调用 sendAsync 必须关闭流
        MailProperties disabledAsyncProps = new MailProperties();
        disabledAsyncProps.getAsync().setEnabled(false);
        MailTemplate noAsyncTemplate = new MailTemplate(accountRegistry, disabledAsyncProps);
        AtomicBoolean closed7 = new AtomicBoolean(false);
        assertThrows(MailSendException.class, () ->
                noAsyncTemplate.sendAsync(new MailMessage()
                        .to("g@example.com")
                        .subject("s")
                        .text("t")
                        .attach("g.txt", trackingStream(closed7))));
        assertTrue(closed7.get(), "异步禁用导致 sendAsync 抛出异常时必须关闭附件流");
    }

    @Test
    @DisplayName("测试 AuthMode.AUTO 无凭据与 AuthMode.NONE 对接无认证中继服务器开箱发信")
    void testUnauthenticatedSmtpRelayWithAutoAndNoneAuthMode() throws Exception {
        com.icegreen.greenmail.util.GreenMail openRelay = new com.icegreen.greenmail.util.GreenMail(
                com.icegreen.greenmail.util.ServerSetupTest.SMTP.dynamicPort());
        openRelay.start();
        try {
            int relayPort = openRelay.getSmtp().getPort();
            MailAccount autoNoCredAccount = MailAccount.builder()
                    .name("open-relay-auto")
                    .host("127.0.0.1")
                    .port(relayPort)
                    .ssl(false)
                    .defaultFrom("relay@internal.local")
                    .build();
            MailAccount managedAuto = accountRegistry.getOrCreateDynamic("open-relay-auto", autoNoCredAccount, mailProperties);
            MailSendResult r1 = mailTemplate.sendWithAccount(managedAuto, new MailMessage()
                    .to("internal1@internal.local")
                    .subject("免认证中继 AUTO")
                    .text("auto relay body"));
            assertTrue(r1.isSuccess());

            MailAccount forcedNoneAccount = MailAccount.builder()
                    .name("open-relay-none")
                    .host("127.0.0.1")
                    .port(relayPort)
                    .username("ignored-user@internal.local")
                    .password("ignored-pwd")
                    .authMode(io.github.atengk.boot.mail.model.AuthMode.NONE)
                    .ssl(false)
                    .defaultFrom("relay-none@internal.local")
                    .build();
            MailAccount managedNone = accountRegistry.getOrCreateDynamic("open-relay-none", forcedNoneAccount, mailProperties);
            MailSendResult r2 = mailTemplate.sendWithAccount(managedNone, new MailMessage()
                    .to("internal2@internal.local")
                    .subject("免认证中继 NONE")
                    .text("none relay body"));
            assertTrue(r2.isSuccess());

            assertTrue(openRelay.waitForIncomingEmail(5000, 2));
            assertEquals(2, openRelay.getReceivedMessages().length);
        } finally {
            openRelay.stop();
        }
    }

    @Test
    @DisplayName("测试 AuthMode.XOAUTH2 动态签发令牌并在认证失败时自动刷新重试一次")
    void testOAuth2TokenRefreshAndSingleRetryOnAuthFailure() throws Exception {
        AtomicInteger getCalls = new AtomicInteger(0);
        AtomicInteger refreshCalls = new AtomicInteger(0);
        AtomicInteger sendCalls = new AtomicInteger(0);
        List<String> observedTokens = new ArrayList<>();

        io.github.atengk.boot.mail.provider.MailAccessTokenProvider tokenProvider =
                new io.github.atengk.boot.mail.provider.MailAccessTokenProvider() {
                    @Override
                    public String getAccessToken(MailAccount account) {
                        getCalls.incrementAndGet();
                        return "stale-oauth-token";
                    }

                    @Override
                    public String refreshAccessToken(MailAccount account) {
                        refreshCalls.incrementAndGet();
                        return "fresh-oauth-token";
                    }
                };

        int smtpPort = greenMail.getSmtp().getPort();
        JavaMailSenderImpl realSender = new JavaMailSenderImpl();
        realSender.setHost("127.0.0.1");
        realSender.setPort(smtpPort);
        realSender.setUsername("test@atengk.com");
        realSender.setPassword("password");

        JavaMailSenderImpl oauthSender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                int attempt = sendCalls.incrementAndGet();
                observedTokens.add(getPassword());
                if (attempt == 1) {
                    throw new org.springframework.mail.MailAuthenticationException("XOAUTH2 token expired");
                }
                realSender.send(mimeMessage);
            }
        };

        MailAccount oauthAccount = MailAccount.builder()
                .name("oauth-mail")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("test@atengk.com")
                .authMode(io.github.atengk.boot.mail.model.AuthMode.XOAUTH2)
                .defaultFrom("oauth@atengk.com")
                .sender(oauthSender)
                .build();
        accountRegistry.register(oauthAccount);

        MailTemplate oauthTemplate = new MailTemplate(
                accountRegistry, mailProperties, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, tokenProvider, null, null, null, null);
        try {
            MailSendResult result = oauthTemplate.send(msg -> msg
                    .account("oauth-mail")
                    .to("oauth-user@example.com")
                    .subject("XOAUTH2 令牌刷新测试")
                    .text("token refreshed ok"));
            assertTrue(result.isSuccess());
            assertEquals(1, getCalls.get());
            assertEquals(1, refreshCalls.get());
            assertEquals(2, sendCalls.get());
            assertEquals(List.of("stale-oauth-token", "fresh-oauth-token"), observedTokens);
            assertTrue(greenMail.waitForIncomingEmail(5000, 1));

            // 刷新后仍认证失败，不得无限重试，直接抛出 MAIL_AUTHENTICATION_ERROR
            JavaMailSenderImpl alwaysFailOAuth = new JavaMailSenderImpl() {
                @Override
                public void send(MimeMessage mimeMessage) {
                    sendCalls.incrementAndGet();
                    throw new org.springframework.mail.MailAuthenticationException("revoked permanently");
                }
            };
            accountRegistry.register(MailAccount.builder()
                    .name("oauth-revoked")
                    .host("127.0.0.1")
                    .port(smtpPort)
                    .username("test@atengk.com")
                    .authMode(io.github.atengk.boot.mail.model.AuthMode.XOAUTH2)
                    .sender(alwaysFailOAuth)
                    .build());

            sendCalls.set(0);
            refreshCalls.set(0);
            MailSendException failEx = assertThrows(MailSendException.class, () ->
                    oauthTemplate.sendText("oauth-revoked", "oauth-fail@example.com", "fail", "body"));
            assertEquals(MailResultCode.MAIL_AUTHENTICATION_ERROR, failEx.getResultCode());
            assertEquals(1, refreshCalls.get());
            assertEquals(2, sendCalls.get());
        } finally {
            oauthTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试单封 returnPath 改写物理信封 MAIL FROM 且与共享 Session 并发互不串扰，非法格式抛出 MAIL_INVALID_ADDRESS")
    void testReturnPathEnvelopeFromAndSessionIsolation() throws Exception {
        mailProperties.getRetry().setEnabled(true);
        mailProperties.getRetry().setMaxAttempts(2);
        mailProperties.getRetry().setDelay(Duration.ofMillis(10));

        // 1. 非法 returnPath 立即抛出 MAIL_INVALID_ADDRESS
        MailSendException invalidRp = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("rp-bad@example.com")
                        .returnPath("invalid-return-path")
                        .subject("s")
                        .text("t")));
        assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, invalidRp.getResultCode());

        // 2. 并发与串行交叉发送：单封指定 returnPath，其余不指定，互不污染共享 Session
        MailSendResult r1 = mailTemplate.send(msg -> msg
                .to("rp1@example.com")
                .from("notice@atengk.com")
                .returnPath("bounce-order-1@atengk.com")
                .subject("独立退信地址验证")
                .text("body1"));
        assertTrue(r1.isSuccess());

        MailSendResult r2 = mailTemplate.send(msg -> msg
                .to("rp2@example.com")
                .from("notice@atengk.com")
                .subject("共享 Session 无串扰验证")
                .text("body2"));
        assertTrue(r2.isSuccess());

        assertNull(accountRegistry.getDefault().getSender().getSession().getProperties().getProperty("mail.smtp.from"),
                "共享 Session 严禁被单封 returnPath 污染");

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        assertEquals(2, msgs.length);
        assertTrue(msgs[0].getHeader("Return-Path", null).contains("bounce-order-1@atengk.com"));
        assertTrue(msgs[1].getHeader("Return-Path", null).contains("notice@atengk.com"));
        assertFalse(msgs[1].getHeader("Return-Path", null).contains("bounce-order-1@atengk.com"));
    }

    @Test
    @DisplayName("测试 List-Unsubscribe 与 List-Unsubscribe-Post 退订合规标头写入、换行清洗与 mailto 地址校验")
    void testListUnsubscribeHeadersAndSanitization() throws Exception {
        // 1. 非法 mailto 地址拦截（含未闭合尖括号畸形场景）
        for (String badUnsub : List.of("<mailto:invalid-email-addr>", "<mailto:invalid-unclosed")) {
            MailSendException invalidMailto = assertThrows(MailSendException.class, () ->
                    mailTemplate.send(msg -> msg
                            .to("unsub-bad@example.com")
                            .listUnsubscribe(badUnsub)
                            .subject("s")
                            .text("t")));
            assertEquals(MailResultCode.MAIL_INVALID_ADDRESS, invalidMailto.getResultCode());
        }

        // 2. 合法 HTTPS + mailto + One-Click 且带注入字符时清洗后写入真实报文
        MailSendResult result = mailTemplate.send(msg -> msg
                .to("unsub-ok@example.com")
                .subject("订阅周报")
                .html("<p>内容</p>")
                .listUnsubscribe("<https://example.com/unsub?id=99>, <mailto:unsub@atengk.com?subject=unsubscribe>\r\nX-Injected-Unsub: evil")
                .listUnsubscribePost("List-Unsubscribe=One-Click\r\nX-Injected-Post: evil"));
        assertTrue(result.isSuccess());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        String unsub = received.getHeader("List-Unsubscribe", null);
        String unsubPost = received.getHeader("List-Unsubscribe-Post", null);
        assertNotNull(unsub);
        assertNotNull(unsubPost);
        assertTrue(unsub.contains("https://example.com/unsub?id=99"));
        assertTrue(unsub.contains("mailto:unsub@atengk.com"));
        assertFalse(unsub.contains("\r") || unsub.contains("\n"));
        assertTrue(unsubPost.startsWith("List-Unsubscribe=One-Click"));
        assertFalse(unsubPost.contains("\r") || unsubPost.contains("\n"));
        assertNull(received.getHeader("X-Injected-Unsub"));
        assertNull(received.getHeader("X-Injected-Post"));
    }

    private ByteArrayInputStream trackingStream(AtomicBoolean closedFlag) {
        return new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closedFlag.set(true);
                super.close();
            }
        };
    }

    @Test
    @DisplayName("测试未知大小内联流超出单个上限抛出 MAIL_ATTACHMENT_SIZE_EXCEEDED，多流累加超总上限抛出 MAIL_TOTAL_SIZE_EXCEEDED")
    void testInlineBoundedStreamAndCumulativeUnknownStreamTotalSizeLimit() {
        mailProperties.setMaxAttachmentSize(DataSize.ofBytes(100));
        mailProperties.setMaxTotalSize(DataSize.ofBytes(120));

        // 1. 未知大小内联流超出单个上限 (150 > 100)
        ByteArrayInputStream bigInline = new ByteArrayInputStream(new byte[150]);
        MailSendException inlineEx = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("inline-limit@example.com")
                        .subject("内联流超限")
                        .html("<img src='cid:bigImg'/>")
                        .inline(io.github.atengk.boot.mail.model.MailInlineResource.of("bigImg", bigInline, "image/png"))));
        assertEquals(MailResultCode.MAIL_ATTACHMENT_SIZE_EXCEEDED, inlineEx.getResultCode());

        // 2. 多个未知大小流（内联 80B + 附件 80B），单个均 <= 100B，但累加 160B > 120B 触发总容量熔断
        ByteArrayInputStream inlineStream = new ByteArrayInputStream(new byte[80]);
        ByteArrayInputStream attachStream = new ByteArrayInputStream(new byte[80]);
        MailSendException totalEx = assertThrows(MailSendException.class, () ->
                mailTemplate.send(msg -> msg
                        .to("total-limit@example.com")
                        .subject("累加流总大小超限")
                        .html("<img src='cid:pic'/>")
                        .inline(io.github.atengk.boot.mail.model.MailInlineResource.of("pic", inlineStream, "image/png"))
                        .attach("part.bin", attachStream)));
        assertEquals(MailResultCode.MAIL_TOTAL_SIZE_EXCEEDED, totalEx.getResultCode());
    }

    @Test
    @DisplayName("测试三阶防护：多收件人 To+Cc+Bcc 去重计费、限流不重试与日配额熔断事件发布")
    void testRateLimitQuotaDeduplicatedRecipientsAndNoRetry() {
        MailProperties guardedProps = new MailProperties();
        guardedProps.getRetry().setEnabled(true);
        guardedProps.getRetry().setMaxAttempts(3);
        guardedProps.getRetry().setDelay(Duration.ofMillis(10));

        MailRateLimitProperties rateProps = guardedProps.getRateLimit();
        rateProps.setEnabled(true);
        rateProps.setAccountCapacity(4L);
        rateProps.setAccountPermitsPerSecond(0.001d);
        rateProps.setRecipientMaxCount(5L);

        MailQuotaProperties quotaProps = guardedProps.getQuota();
        quotaProps.setEnabled(true);
        quotaProps.setDailyMaxCount(5L);

        AtomicInteger rateLimiterInvocations = new AtomicInteger();
        InMemoryMailRateLimiter innerLimiter = new InMemoryMailRateLimiter(rateProps);
        MailRateLimiter countingLimiter = new MailRateLimiter() {
            @Override
            public boolean tryAcquire(MailAccount account, MailMessage message, java.util.Collection<String> deduplicatedRecipients) {
                rateLimiterInvocations.incrementAndGet();
                return innerLimiter.tryAcquire(account, message, deduplicatedRecipients);
            }
        };
        InMemoryMailDailyQuotaBreaker quotaBreaker = new InMemoryMailDailyQuotaBreaker(quotaProps);

        List<MailQuotaExceededEvent> quotaEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof MailQuotaExceededEvent qee) {
                quotaEvents.add(qee);
            }
        };

        MailTemplate guardedTemplate = new MailTemplate(
                accountRegistry, guardedProps, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null,
                publisher, null, null, countingLimiter, quotaBreaker, null
        );

        try {
            // 1. To+Cc+Bcc 含重复与显示名（大小写不同）：实际去重后仅 3 个物理邮箱（a, b, c）
            MailSendResult r1 = guardedTemplate.send(msg -> msg
                    .to("Alice <A@example.com>", "b@example.com")
                    .cc("a@EXAMPLE.com", "c@example.com")
                    .bcc("B@example.com")
                    .subject("多收件人去重计费")
                    .text("dedup body"));
            assertTrue(r1.isSuccess());
            assertEquals(3L, quotaBreaker.getCurrentCount(null));
            assertEquals(1, rateLimiterInvocations.get());

            // 2. 桶余量 1，申请 2 个去重收件人立即触发 MAIL_RATE_LIMITED，且配置 retry.enabled=true 时绝不重试（仅调用 1 次）
            MailSendException rateEx = assertThrows(MailSendException.class, () ->
                    guardedTemplate.send(msg -> msg
                            .to("d@example.com", "e@example.com")
                            .subject("触发限流")
                            .text("blocked")));
            assertEquals(MailResultCode.MAIL_RATE_LIMITED, rateEx.getResultCode());
            assertEquals(2, rateLimiterInvocations.get());
            assertEquals(3L, quotaBreaker.getCurrentCount(null));
            assertTrue(quotaEvents.isEmpty());

            // 3. 复位限流后剩余配额 2（3/5），发给 3 个去重收件人应立即触顶配额熔断且发布 MailQuotaExceededEvent，同样不重试
            innerLimiter.reset();
            AtomicInteger quotaBreakerCalls = new AtomicInteger();
            MailDailyQuotaBreaker countingQuota = new MailDailyQuotaBreaker() {
                @Override
                public QuotaCheckResult tryConsume(MailAccount account, MailMessage message, int recipientCount) {
                    quotaBreakerCalls.incrementAndGet();
                    return quotaBreaker.tryConsume(account, message, recipientCount);
                }

                @Override
                public long getCurrentCount(java.time.LocalDate date) {
                    return quotaBreaker.getCurrentCount(date);
                }
            };

            MailTemplate quotaGuardedTemplate = new MailTemplate(
                    accountRegistry, guardedProps, new SimpleMailTemplateRenderer(),
                    new TenantMailAccountResolver(), null, null, null,
                    publisher, null, null, innerLimiter, countingQuota, null
            );
            MailSendException quotaEx = assertThrows(MailSendException.class, () ->
                    quotaGuardedTemplate.send(msg -> msg
                            .to("x1@example.com")
                            .cc("x2@example.com")
                            .bcc("x3@example.com")
                            .subject("触发日配额熔断")
                            .text("over quota")));
            assertEquals(MailResultCode.MAIL_QUOTA_EXCEEDED, quotaEx.getResultCode());
            assertEquals(1, quotaBreakerCalls.get());
            assertEquals(1, quotaEvents.size());
            MailQuotaExceededEvent event = quotaEvents.get(0);
            assertEquals("default", event.getAccountName());
            assertEquals(5L, event.getDailyLimit());
            assertEquals(3L, event.getCurrentCount());
            assertEquals(3, event.getRequestedCount());
            assertEquals(3L, quotaBreaker.getCurrentCount(null));
        } finally {
            guardedTemplate.destroy();
            innerLimiter.reset();
            quotaBreaker.reset();
        }
    }

    @Test
    @DisplayName("测试沙箱 logOnly=true 自动豁免频控、日配额与幂等拦截")
    void testSandboxLogOnlyExemptsGuards() {
        MailProperties sandboxProps = new MailProperties();
        MailSandboxProperties sandbox = sandboxProps.getSandbox();
        sandbox.setEnabled(true);
        sandbox.setLogOnly(true);

        MailRateLimitProperties rateProps = sandboxProps.getRateLimit();
        rateProps.setEnabled(true);
        MailQuotaProperties quotaProps = sandboxProps.getQuota();
        quotaProps.setEnabled(true);
        quotaProps.setDailyMaxCount(1L);
        MailIdempotentProperties idemProps = sandboxProps.getIdempotent();
        idemProps.setEnabled(true);

        AtomicInteger guardCalled = new AtomicInteger();
        MailRateLimiter strictFailLimiter = (a, m, r) -> {
            guardCalled.incrementAndGet();
            return false;
        };
        InMemoryMailDailyQuotaBreaker quotaBreaker = new InMemoryMailDailyQuotaBreaker(quotaProps);
        InMemoryMailIdempotentGuard idemGuard = new InMemoryMailIdempotentGuard(idemProps);
        MailSandboxInterceptor sandboxInterceptor = new MailSandboxInterceptor(sandbox);

        MailTemplate sandboxTemplate = new MailTemplate(
                accountRegistry, sandboxProps, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null, null, null,
                List.of(sandboxInterceptor), strictFailLimiter, quotaBreaker, idemGuard
        );

        try {
            // 连续以相同 bizId 在 logOnly 模式下发送多次，均直接放行并零扣减配额与频控
            for (int i = 0; i < 3; i++) {
                MailSendResult res = sandboxTemplate.send(msg -> msg
                        .bizId("SANDBOX-BIZ-1")
                        .to("real-user@example.com")
                        .subject("沙箱纯日志豁免测试")
                        .text("body"));
                assertTrue(res.isSuccess());
            }
            assertEquals(0, guardCalled.get());
            assertEquals(0L, quotaBreaker.getCurrentCount(null));
            assertFalse(idemGuard.isLocked("biz:SANDBOX-BIZ-1"));
        } finally {
            sandboxTemplate.destroy();
            quotaBreaker.reset();
            idemGuard.clear();
        }
    }

    @Test
    @DisplayName("测试 bizId 幂等防重：默认仅拦重复 bizId、无 bizId 放行、物理失败自动 releaseLock 允许重试")
    void testIdempotentGuardBizIdBehaviorAndReleaseOnFailure() throws Exception {
        MailProperties props = new MailProperties();
        MailIdempotentProperties idemProps = props.getIdempotent();
        idemProps.setEnabled(true);
        idemProps.setRequireBizId(true);

        InMemoryMailIdempotentGuard innerGuard = new InMemoryMailIdempotentGuard(idemProps);
        AtomicInteger releaseCount = new AtomicInteger();
        MailIdempotentGuard spyGuard = new MailIdempotentGuard() {
            @Override
            public boolean tryAcquire(MailAccount account, MailMessage message, String idempotentKey) {
                return innerGuard.tryAcquire(account, message, idempotentKey);
            }

            @Override
            public void releaseLock(MailAccount account, MailMessage message, String idempotentKey) {
                releaseCount.incrementAndGet();
                innerGuard.releaseLock(account, message, idempotentKey);
            }

            @Override
            public boolean isLocked(String idempotentKey) {
                return innerGuard.isLocked(idempotentKey);
            }
        };

        MailTemplate template = new MailTemplate(
                accountRegistry, props, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null,
                null, null, null, null, null, spyGuard
        );

        try {
            // 1. 未传 bizId 时（require-biz-id=true），完全一致的内容允许反复发送
            assertTrue(template.sendText("notice@example.com", "通用通知", "内容相同").isSuccess());
            assertTrue(template.sendText("notice@example.com", "通用通知", "内容相同").isSuccess());

            // 2. 携带相同 bizId 首次放行，二次幂等拦截（不重复发出第 4 封物理邮件，也不误释放首封锁）
            MailSendResult ok = template.send(msg -> msg.bizId("PAY-2026-001").to("vip@example.com").subject("支付回执").text("ok"));
            assertTrue(ok.isSuccess());
            MailSendException dupEx = assertThrows(MailSendException.class, () ->
                    template.send(msg -> msg.bizId("PAY-2026-001").to("vip@example.com").subject("支付回执").text("ok")));
            assertEquals(MailResultCode.MAIL_IDEMPOTENT_REJECTED, dupEx.getResultCode());
            assertEquals(0, releaseCount.get());
            assertTrue(spyGuard.isLocked("biz:PAY-2026-001"));

            // 3. 物理发送失败自动调用 releaseLock 允许后续补偿重试
            JavaMailSenderImpl brokenSender = new JavaMailSenderImpl();
            brokenSender.setHost("127.0.0.1");
            brokenSender.setPort(1); // 无效不可达端口触发底层发送失败
            brokenSender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
            MailAccount brokenAccount = MailAccount.builder()
                    .name("broken")
                    .host("127.0.0.1")
                    .port(1)
                    .sender(brokenSender)
                    .build();

            assertThrows(MailSendException.class, () ->
                    template.sendWithAccount(brokenAccount, new MailMessage()
                            .bizId("COMPENSATE-007")
                            .to("retry@example.com")
                            .subject("待补偿发送")
                            .text("payload")));
            assertEquals(1, releaseCount.get());
            assertFalse(spyGuard.isLocked("biz:COMPENSATE-007"));

            // 切换回正常可用账号携带同一 bizId 补偿发送成功
            MailSendResult recovered = template.send(msg -> msg
                    .bizId("COMPENSATE-007")
                    .to("retry@example.com")
                    .subject("补偿发送成功")
                    .text("payload"));
            assertTrue(recovered.isSuccess());
            assertTrue(spyGuard.isLocked("biz:COMPENSATE-007"));

            // 4. require-biz-id=false 时，未传 bizId 且内容（含附件元信息）一致会被摘要幂等拦截，不同附件放行
            idemProps.setRequireBizId(false);
            assertTrue(template.sendText("digest@example.com", "相同主题", "相同正文").isSuccess());
            MailSendException digestDup = assertThrows(MailSendException.class, () ->
                    template.sendText("digest@example.com", "相同主题", "相同正文"));
            assertEquals(MailResultCode.MAIL_IDEMPOTENT_REJECTED, digestDup.getResultCode());

            // 正文相同但携带不同附件时摘要不同应正常放行
            MailSendResult withAttach = template.send(msg -> msg
                    .to("digest@example.com")
                    .subject("相同主题")
                    .text("相同正文")
                    .attach("contract-v2.txt", "v2".getBytes(StandardCharsets.UTF_8), "text/plain"));
            assertTrue(withAttach.isSuccess());

            assertTrue(greenMail.waitForIncomingEmail(5000, 6));
        } finally {
            template.destroy();
            innerGuard.clear();
        }
    }

    @Test
    @DisplayName("测试主账号瞬态故障重试耗尽后按 backupAccounts 顺序顺延容灾及全链异常保留完整因果链")
    void testCascadeFailoverOrderRetryExhaustionAndCausalChain() throws Exception {
        MailProperties failoverProps = new MailProperties();
        failoverProps.getRetry().setEnabled(true);
        failoverProps.getRetry().setMaxAttempts(2);
        failoverProps.getRetry().setDelay(Duration.ofMillis(5));
        failoverProps.getRetry().setJitter(false);

        AtomicInteger primaryAttempts = new AtomicInteger();
        AtomicInteger deadBackupAttempts = new AtomicInteger();
        List<String> attemptSequence = new ArrayList<>();

        JavaMailSenderImpl primaryFailingSender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                primaryAttempts.incrementAndGet();
                attemptSequence.add("primary");
                throw new org.springframework.mail.MailSendException("SMTP 421 Service not available",
                        new java.net.SocketTimeoutException("connect timed out"));
            }
        };
        JavaMailSenderImpl deadBackupSender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                deadBackupAttempts.incrementAndGet();
                attemptSequence.add("dead-backup");
                throw new org.springframework.mail.MailSendException("Connection refused to dead backup");
            }
        };

        MailAccountRegistry failoverRegistry = new MailAccountRegistry("primary");
        failoverRegistry.register(MailAccount.builder()
                .name("primary")
                .host("smtp.primary.example.com")
                .port(465)
                .defaultFrom("notice@primary.com")
                .backupAccounts(List.of("dead-backup", "healthy-backup"))
                .sender(primaryFailingSender)
                .build());
        failoverRegistry.register(MailAccount.builder()
                .name("dead-backup")
                .host("smtp.dead.example.com")
                .port(465)
                .defaultFrom("dead@dead.com")
                .sender(deadBackupSender)
                .build());
        failoverRegistry.register(MailAccount.builder()
                .name("healthy-backup")
                .host("127.0.0.1")
                .port(greenMail.getSmtp().getPort())
                .username("ops@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("ops@atengk.com")
                .defaultFromName("备用运维中心")
                .sender(accountRegistry.get("ops").getSender())
                .build());

        MailTemplate failoverTemplate = new MailTemplate(failoverRegistry, failoverProps);
        try {
            // 1. 主账号耗尽 2 次重试 -> dead-backup 耗尽 2 次重试 -> 顺延切换至 healthy-backup 成功投递
            MailSendResult ok = failoverTemplate.sendText("failover-ok@example.com", "主备顺延容灾", "payload");
            assertTrue(ok.isSuccess());
            assertEquals(2, primaryAttempts.get(), "主账号应先耗尽自身 2 次重试再顺延");
            assertEquals(2, deadBackupAttempts.get());
            assertEquals(List.of("primary", "primary", "dead-backup", "dead-backup"), attemptSequence);

            assertTrue(greenMail.waitForIncomingEmail(5000, 1));

            // 2. 全链均失败时，异常因果链必须完整保留主账号根因及备用账号 Suppressed 异常
            failoverRegistry.register(MailAccount.builder()
                    .name("only-dead-chain")
                    .host("smtp.primary.example.com")
                    .port(465)
                    .defaultFrom("notice@primary.com")
                    .backupAccounts(List.of("dead-backup"))
                    .sender(primaryFailingSender)
                    .build());

            MailSendException chainEx = assertThrows(MailSendException.class, () ->
                    failoverTemplate.send(msg -> msg
                            .account("only-dead-chain")
                            .to("all-dead@example.com")
                            .subject("全链故障")
                            .text("body")));
            assertEquals(MailResultCode.MAIL_CONNECT_TIMEOUT, chainEx.getResultCode());
            assertNotNull(chainEx.getCause());
            assertTrue(chainEx.getCause().getSuppressed().length >= 1,
                    "全链耗尽时必须把备用账号失败异常附加至 suppressed 因果链");
        } finally {
            failoverTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试认证失败、非法地址、拦截器与三阶防护阻断时立即 Fail-Fast 绝不触发备用切换")
    void testDeterministicFailuresFailFastWithoutFailover() {
        AtomicInteger healthyBackupCalls = new AtomicInteger();
        JavaMailSenderImpl authFailSender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                throw new org.springframework.mail.MailAuthenticationException("535 Authentication failed");
            }
        };
        JavaMailSenderImpl healthyBackupSpy = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                healthyBackupCalls.incrementAndGet();
            }
        };

        MailAccountRegistry registry = new MailAccountRegistry("auth-primary");
        registry.register(MailAccount.builder()
                .name("auth-primary")
                .host("smtp.primary.example.com")
                .port(465)
                .username("user@primary.com")
                .password("wrong")
                .backupAccounts(List.of("healthy-backup"))
                .sender(authFailSender)
                .build());
        registry.register(MailAccount.builder()
                .name("healthy-backup")
                .host("smtp.backup.example.com")
                .port(465)
                .defaultFrom("backup@backup.com")
                .sender(healthyBackupSpy)
                .build());

        MailProperties props = new MailProperties();
        props.getRetry().setEnabled(true);
        props.getRetry().setMaxAttempts(2);
        props.getRetry().setDelay(Duration.ofMillis(5));

        MailSendInterceptor blockingInterceptor = msg -> false;
        MailRateLimiter rejectLimiter = (a, m, r) -> false;

        MailTemplate authTemplate = new MailTemplate(registry, props);
        MailTemplate interceptorTemplate = new MailTemplate(registry, props, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null, null, null, List.of(blockingInterceptor));
        MailTemplate rateRejectTemplate = new MailTemplate(registry, props, new SimpleMailTemplateRenderer(),
                new TenantMailAccountResolver(), null, null, null, null, null, null, rejectLimiter, null, null);

        try {
            // 1. 认证失败 Fail-Fast
            MailSendException authEx = assertThrows(MailSendException.class, () ->
                    authTemplate.sendText("failfast@example.com", "auth", "body"));
            assertEquals(MailResultCode.MAIL_AUTHENTICATION_ERROR, authEx.getResultCode());

            // 2. 空收件人 / 非法地址 Fail-Fast
            assertThrows(MailSendException.class, () -> authTemplate.send(msg -> msg.to(" ").subject("s").text("t")));
            assertThrows(MailSendException.class, () -> authTemplate.sendText("bad-email", "s", "t"));

            // 3. 拦截器阻断 Fail-Fast
            assertThrows(MailSendException.class, () -> interceptorTemplate.sendText("ok@example.com", "blocked", "body"));

            // 4. 限流拒绝 Fail-Fast
            MailSendException rateEx = assertThrows(MailSendException.class, () ->
                    rateRejectTemplate.sendText("ok@example.com", "limited", "body"));
            assertEquals(MailResultCode.MAIL_RATE_LIMITED, rateEx.getResultCode());

            assertEquals(0, healthyBackupCalls.get(), "任何确定性失败均不得顺延切换备用账号");
        } finally {
            authTemplate.destroy();
            interceptorTemplate.destroy();
            rateRejectTemplate.destroy();
        }
    }

    @Test
    @DisplayName("测试 SPF/DMARC 域名对齐（override-from-on-switch=true/false）、Reply-To 保留与多跳原始发件人保持")
    void testFailoverSpfDmarcFromAlignmentAndReplyToPreservation() throws Exception {
        JavaMailSenderImpl primaryFailing = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                throw new org.springframework.mail.MailSendException("Primary gateway down",
                        new java.net.ConnectException("Connection refused"));
            }
        };
        JavaMailSenderImpl deadHop = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                throw new org.springframework.mail.MailSendException("Dead hop unavailable");
            }
        };

        MailAccountRegistry registry = new MailAccountRegistry("primary");
        registry.register(MailAccount.builder()
                .name("primary")
                .host("smtp.primary.example.com")
                .port(465)
                .defaultFrom("notice@primary.com")
                .backupAccounts(List.of("dead-hop"))
                .sender(primaryFailing)
                .build());
        registry.register(MailAccount.builder()
                .name("dead-hop")
                .host("smtp.deadhop.example.com")
                .port(465)
                .defaultFrom("intermediate@deadhop.com")
                .backupAccounts(List.of("rescue"))
                .sender(deadHop)
                .build());
        registry.register(MailAccount.builder()
                .name("rescue")
                .host("127.0.0.1")
                .port(greenMail.getSmtp().getPort())
                .username("ops@atengk.com")
                .password("password")
                .ssl(false)
                .defaultFrom("ops@atengk.com")
                .defaultFromName("备用容灾发件箱")
                .sender(accountRegistry.get("ops").getSender())
                .build());

        // 1. 默认 override-from-on-switch=true 且无显式 replyTo：多跳切换后 From 对齐为 ops@atengk.com，Reply-To 保留最原始 sender@primary.com
        MailProperties overrideTrueProps = new MailProperties();
        assertTrue(overrideTrueProps.getFailover().isOverrideFromOnSwitch());
        MailTemplate overrideTemplate = new MailTemplate(registry, overrideTrueProps);
        try {
            overrideTemplate.send(msg -> msg
                    .to("spf1@example.com")
                    .from("sender@primary.com")
                    .subject("默认域名对齐测试")
                    .text("body1"));

            // 2. 已有显式 replyTo 时尊重原值
            overrideTemplate.send(msg -> msg
                    .to("spf2@example.com")
                    .from("sender@primary.com")
                    .replyTo("support@primary.com")
                    .subject("显式 Reply-To 尊重原值")
                    .text("body2"));
        } finally {
            overrideTemplate.destroy();
        }

        // 3. override-from-on-switch=false：切换至备用通道投递但保留原 From 地址与原显示名（不串用备用 defaultFromName）
        MailProperties overrideFalseProps = new MailProperties();
        overrideFalseProps.getFailover().setOverrideFromOnSwitch(false);
        MailTemplate preserveFromTemplate = new MailTemplate(registry, overrideFalseProps);
        try {
            preserveFromTemplate.send(msg -> msg
                    .to("spf3@example.com")
                    .from("主线客服 <sender@primary.com>")
                    .subject("关闭域名覆盖测试")
                    .text("body3"));
        } finally {
            preserveFromTemplate.destroy();
        }

        assertTrue(greenMail.waitForIncomingEmail(5000, 3));
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(3, received.length);

        assertTrue(received[0].getFrom()[0].toString().contains("ops@atengk.com"));
        assertEquals("sender@primary.com", ((jakarta.mail.internet.InternetAddress) received[0].getReplyTo()[0]).getAddress());

        assertTrue(received[1].getFrom()[0].toString().contains("ops@atengk.com"));
        assertEquals("support@primary.com", ((jakarta.mail.internet.InternetAddress) received[1].getReplyTo()[0]).getAddress());

        jakarta.mail.internet.InternetAddress thirdFrom = (jakarta.mail.internet.InternetAddress) received[2].getFrom()[0];
        assertEquals("sender@primary.com", thirdFrom.getAddress());
        assertEquals("主线客服", thirdFrom.getPersonal());
        assertFalse(received[2].getFrom()[0].toString().contains("ops@atengk.com"));
        assertFalse(received[2].getFrom()[0].toString().contains("备用容灾发件箱"));
    }

    @Test
    @DisplayName("测试 backupAccounts 自引用、双向互备环路（A -> B -> A）防环隔离与缺失账号平滑跳过")
    void testFailoverCyclePreventionAndMissingAccountSkip() throws Exception {
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        JavaMailSenderImpl failingSenderA = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                aCalls.incrementAndGet();
                throw new org.springframework.mail.MailSendException("A down");
            }
        };
        JavaMailSenderImpl failingSenderB = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                bCalls.incrementAndGet();
                throw new org.springframework.mail.MailSendException("B down");
            }
        };

        MailAccountRegistry registry = new MailAccountRegistry("loop-a");
        registry.register(MailAccount.builder()
                .name("loop-a")
                .host("smtp.a.example.com")
                .port(465)
                .backupAccounts(List.of("loop-a", "missing-account", "loop-b"))
                .sender(failingSenderA)
                .build());
        registry.register(MailAccount.builder()
                .name("loop-b")
                .host("smtp.b.example.com")
                .port(465)
                .backupAccounts(List.of("loop-a", "loop-b"))
                .sender(failingSenderB)
                .build());

        MailTemplate loopTemplate = new MailTemplate(registry, new MailProperties());
        try {
            // 1. 纯双向环路 A -> [A, missing, B] -> [A, B]：每个实体账号仅精确执行 1 次，绝不死循环
            assertThrows(MailSendException.class, () ->
                    loopTemplate.sendText("cycle@example.com", "环路熔断校验", "body"));
            assertEquals(1, aCalls.get());
            assertEquals(1, bCalls.get());

            // 2. 若 B 后方还挂有健康备用 ops，绕过已访问环路 A 后应顺延至 ops 成功投递
            aCalls.set(0);
            bCalls.set(0);
            registry.register(MailAccount.builder()
                    .name("loop-b")
                    .host("smtp.b.example.com")
                    .port(465)
                    .backupAccounts(List.of("loop-a", "ops"))
                    .sender(failingSenderB)
                    .build());
            registry.register(MailAccount.builder()
                    .name("ops")
                    .host("127.0.0.1")
                    .port(greenMail.getSmtp().getPort())
                    .username("ops@atengk.com")
                    .password("password")
                    .ssl(false)
                    .defaultFrom("ops@atengk.com")
                    .sender(accountRegistry.get("ops").getSender())
                    .build());

            MailSendResult ok = loopTemplate.sendText("escape-cycle@example.com", "绕环成功送达", "body");
            assertTrue(ok.isSuccess());
            assertEquals(1, aCalls.get());
            assertEquals(1, bCalls.get());
            assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        } finally {
            loopTemplate.destroy();
        }
    }

    private void collectAttachmentFilenames(Part part, List<String> names) throws Exception {
        if (part.getFileName() != null) {
            names.add(MimeUtility.decodeText(part.getFileName()));
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collectAttachmentFilenames(mp.getBodyPart(i), names);
            }
        }
    }
}
