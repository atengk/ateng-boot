package io.github.atengk.boot.mail.template;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
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
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

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
        TenantContextHolder.clear();
        accountRegistry.clearDynamic();
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
}
