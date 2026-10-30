package io.github.atengk.boot.mail.template;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import io.github.atengk.boot.mail.event.MailAccountEvictEvent;
import io.github.atengk.boot.mail.event.MailSendEvent;
import io.github.atengk.boot.mail.event.MailSendFailedEvent;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.listener.MailAccountEvictListener;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailAttachment;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.MailSendResultItem;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import io.github.atengk.boot.mail.properties.MailProperties;
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
}
