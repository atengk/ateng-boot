package io.github.atengk.boot.sample.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.SendMailRequest;
import io.github.atengk.boot.sample.mail.handler.SampleMailFailureHandler;
import io.github.atengk.boot.sample.mail.interceptor.SampleAuditMailInterceptor;
import io.github.atengk.boot.sample.mail.listener.SampleMailEventListener;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮件场景端到端真实集成测试
 * <p>
 * 依托嵌入式 GreenMail 邮件服务器与 MockMvc，端到端全真验证 Controller 接口、
 * Service 业务编排、HTML 模板渲染、附件挂载、拦截器 SPI、事件生命周期广播与事务感知发信。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("邮件基础设施端到端自验集成测试")
class SampleMailIntegrationTest {

    private static final GreenMail greenMail;
    private static final int smtpPort;

    static {
        ServerSetup setup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        greenMail = new GreenMail(setup)
                .withConfiguration(GreenMailConfiguration.aConfig()
                        .withUser("default@example.com", "secret-password-placeholder")
                        .withUser("ops@example.com", "secret-password-placeholder"));
        greenMail.start();
        smtpPort = greenMail.getSmtp().getPort();
    }

    @AfterAll
    static void tearDownAll() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @DynamicPropertySource
    static void configureMailProperties(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.mail.accounts.default.port", () -> smtpPort);
        registry.add("ateng.boot.mail.accounts.ops.port", () -> smtpPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MailScenarioService mailScenarioService;

    @Autowired
    private SampleMailEventListener eventListener;

    @Autowired
    private SampleMailFailureHandler failureHandler;

    @BeforeEach
    @AfterEach
    void cleanState() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        eventListener.reset();
        failureHandler.reset();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. Controller 纯文本邮件发信：校验统一契约 Result、自定义审计拦截器标头注入与事件广播")
    void shouldSendSimpleMailViaController() throws Exception {
        SendMailRequest req = SendMailRequest.builder()
                .to("recipient-simple@example.com")
                .subject("测试文本邮件")
                .content("这是一封自动化测试纯文本邮件")
                .build();

        mockMvc.perform(post("/api/mail/simple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messageId").isNotEmpty());

        // 校验 GreenMail 物理接收情况
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);

        MimeMessage msg = messages[0];
        assertThat(msg.getSubject()).isEqualTo("测试文本邮件");
        assertThat(msg.getHeader(SampleAuditMailInterceptor.AUDIT_HEADER_KEY)[0])
                .isEqualTo(SampleAuditMailInterceptor.AUDIT_HEADER_VALUE);
        assertThat(msg.getContent().toString()).contains("这是一封自动化测试纯文本邮件");

        // 校验生命周期事件广播
        assertThat(eventListener.getSendingCount().get()).isEqualTo(1);
        assertThat(eventListener.getSentCount().get()).isEqualTo(1);
        assertThat(eventListener.getFailedCount().get()).isEqualTo(0);
    }

    @Test
    @DisplayName("2. Controller 动态 HTML 模板发信：校验 Thymeleaf 模板解析与动态占位符注入")
    void shouldSendTemplateMailViaController() throws Exception {
        SendMailRequest req = SendMailRequest.builder()
                .to("recipient-template@example.com")
                .subject("安全验证码")
                .content("占位正文")
                .username("阿腾")
                .verifyCode("686868")
                .build();

        mockMvc.perform(post("/api/mail/template")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertThat(msg.getSubject()).isEqualTo("安全验证码");

        String body = msg.getContent().toString();
        assertThat(body).contains("阿腾");
        assertThat(body).contains("686868");
        assertThat(body).contains("该验证码有效期为 5 分钟");
    }

    @Test
    @DisplayName("3. Controller 多账号指定通道发信：切换运维 ops 通道校验发信人与主题前缀隔离")
    void shouldSendOpsMailViaController() throws Exception {
        SendMailRequest req = SendMailRequest.builder()
                .to("recipient-ops@example.com")
                .subject("CPU 使用率超阈值告警")
                .content("服务器集群 CPU 使用率超过 90%，请紧急排查！")
                .build();

        mockMvc.perform(post("/api/mail/ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertThat(msg.getSubject()).isEqualTo("CPU 使用率超阈值告警");
        assertThat(msg.getFrom()[0].toString()).contains("ops@example.com");
    }

    @Test
    @DisplayName("4. Controller 受管异步发信：校验 CompletableFuture 异步处理与异步事件触发")
    void shouldSendAsyncMailViaController() throws Exception {
        SendMailRequest req = SendMailRequest.builder()
                .to("recipient-async@example.com")
                .subject("异步通知任务")
                .content("异步并发发信验证")
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/api/mail/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("5. Service 附件流式装配发信：校验 RFC 6266 大附件挂载与 MIME 结构解析")
    void shouldSendMailWithAttachmentViaService() throws Exception {
        byte[] attachmentBytes = "Hello Ateng Mail Attachment Content".getBytes(StandardCharsets.UTF_8);

        MailSendResult result = mailScenarioService.sendWithAttachment(
                "recipient-attach@example.com",
                "财务对账月报附件",
                "请查收本月财务对账报告",
                "statement-202610.txt",
                attachmentBytes
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();

        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertThat(msg.getContent()).isInstanceOf(Multipart.class);

        Multipart multipart = (Multipart) msg.getContent();
        boolean foundAttachment = false;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            String disposition = bodyPart.getDisposition();
            if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || Part.INLINE.equalsIgnoreCase(disposition)) {
                String fileName = MimeUtility.decodeText(bodyPart.getFileName());
                if ("statement-202610.txt".equals(fileName)) {
                    foundAttachment = true;
                    String content = GreenMailUtil.getBody(bodyPart);
                    assertThat(content.trim()).isEqualTo("Hello Ateng Mail Attachment Content");
                }
            }
        }

        assertThat(foundAttachment).isTrue();
    }

    @Test
    @DisplayName("6. 拦截器黑名单阻断防卫：收件人触碰安全黑名单时阻断发信并触发失败事件")
    void shouldBlockSendingWhenRecipientBlacklisted() {
        SendMailRequest req = SendMailRequest.builder()
                .to(SampleAuditMailInterceptor.BLOCKED_EMAIL)
                .subject("恶意垃圾邮件")
                .content("测试拦截")
                .build();

        assertThatThrownBy(() -> mailScenarioService.sendSimpleText(req.getTo(), req.getSubject(), req.getContent()))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("邮件发送被拦截器阻断");

        // GreenMail 不应收到任何邮件
        assertThat(greenMail.getReceivedMessages()).isEmpty();

        // 校验失败补偿处理器回调
        assertThat(failureHandler.getFailureCount().get()).isEqualTo(1);
        assertThat(failureHandler.getLastFailedMessage().get().getTo()).contains(SampleAuditMailInterceptor.BLOCKED_EMAIL);
        assertThat(failureHandler.getLastCause().get()).isInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("7. 本地事务感知发信（sendAfterCommit）：事务回滚时自动取消防幽灵邮件")
    void shouldCancelMailDeliveryOnTransactionRollback() {
        // 1. 模拟业务事务发生异常回滚
        assertThatThrownBy(() -> mailScenarioService.sendTransactional(
                "recipient-tx@example.com",
                "订单支付成功回执",
                "您的订单已完成支付",
                true // 触发回滚
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("Simulated business transaction rollback");

        // 校验邮件未被发送
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("8. 本地事务感知发信（sendAfterCommit）：事务提交成功后正常发出邮件")
    void shouldDeliverMailOnTransactionCommit() throws Exception {
        // 模拟业务事务正常提交
        mailScenarioService.sendTransactional(
                "recipient-tx-ok@example.com",
                "账户注册成功通知",
                "欢迎加入平台！",
                false // 正常提交
        );

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).contains("账户注册成功通知");
    }

    @Test
    @DisplayName("9. 编程式动态账号发信：演示 DynamicMailSession 与 LRU 物理 Sender 复用")
    void shouldSendMailViaDynamicAccount() throws Exception {
        MailAccount dynamicAccount = MailAccount.builder()
                .name("dynamic-sample-channel")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("default@example.com")
                .password("secret-password-placeholder")
                .ssl(false)
                .defaultFrom("dynamic-sender@example.com")
                .defaultFromName("动态专属客服")
                .build();

        MailSendResult result = mailScenarioService.sendWithDynamicAccount(
                dynamicAccount,
                "recipient-dynamic@example.com",
                "动态账号专属优惠券",
                "请查收您的专属代金券"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).isEqualTo("动态账号专属优惠券");
        assertThat(messages[0].getFrom()[0].toString()).contains("dynamic-sender@example.com");
    }
}
