package io.github.atengk.boot.sample.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailPriority;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.AttachmentMailRequest;
import io.github.atengk.boot.sample.mail.dto.BatchAfterCommitRequest;
import io.github.atengk.boot.sample.mail.dto.ComplianceFreemarkerRequest;
import io.github.atengk.boot.sample.mail.dto.DynamicAccountMailRequest;
import io.github.atengk.boot.sample.mail.dto.FailoverDemoRequest;
import io.github.atengk.boot.sample.mail.dto.HtmlContentMailRequest;
import io.github.atengk.boot.sample.mail.dto.InlineMediaMailRequest;
import io.github.atengk.boot.sample.mail.dto.OutboundGuardDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SalarySlipBatchRequest;
import io.github.atengk.boot.sample.mail.dto.SalarySlipItemRequest;
import io.github.atengk.boot.sample.mail.dto.SandboxDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.dto.SystemAlertMailRequest;
import io.github.atengk.boot.sample.mail.dto.TenantRouteMailRequest;
import io.github.atengk.boot.sample.mail.dto.UserVerificationMailRequest;
import io.github.atengk.boot.sample.mail.handler.SampleMailFailureHandler;
import io.github.atengk.boot.sample.mail.interceptor.SampleAuditMailInterceptor;
import io.github.atengk.boot.sample.mail.listener.SampleMailEventListener;
import io.github.atengk.boot.sample.mail.provider.SampleDatabaseMailAccountProvider;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮件场景端到端真实集成测试
 * <p>
 * 依托嵌入式 GreenMail 邮件服务器与 MockMvc，端到端全真验证 5 大领域 Controller 接口、
 * 业务 DTO JSR-303 防卫、Service 业务编排、HTML 模板渲染、附件挂载、内联资源、拦截器 SPI、
 * 事件生命周期广播与事务感知发信（{@code sendAfterCommit}）。
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
        registry.add("ateng.boot.mail.accounts.default.host", () -> "127.0.0.1");
        registry.add("ateng.boot.mail.accounts.default.port", () -> smtpPort);
        registry.add("ateng.boot.mail.accounts.default.ssl", () -> false);
        registry.add("ateng.boot.mail.accounts.ops.host", () -> "127.0.0.1");
        registry.add("ateng.boot.mail.accounts.ops.port", () -> smtpPort);
        registry.add("ateng.boot.mail.accounts.ops.ssl", () -> false);
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

    @Autowired(required = false)
    private SampleDatabaseMailAccountProvider databaseMailAccountProvider;

    @BeforeEach
    void setUp() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        eventListener.reset();
        failureHandler.reset();
    }

    @AfterEach
    void tearDown() {
        mailScenarioService.clearDynamicAccounts();
        if (databaseMailAccountProvider != null) {
            databaseMailAccountProvider.resetQueryCount();
        }
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    // ==========================================
    // 1. MailBasicController 测试
    // ==========================================

    @Test
    @DisplayName("1. BasicController 纯文本邮件发信：校验统一契约 Result、自定义审计拦截器标头注入与事件广播")
    void shouldSendSimpleMailViaBasicController() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("recipient-simple@example.com")
                .subject("测试文本邮件")
                .content("这是一封自动化测试纯文本邮件")
                .build();

        mockMvc.perform(post("/sample/mail/basic/simple-text")
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
    @DisplayName("2. BasicController 富文本 HTML 发信：校验 HTML 内容正常外发与解析")
    void shouldSendHtmlContentViaBasicController() throws Exception {
        HtmlContentMailRequest req = HtmlContentMailRequest.builder()
                .to("recipient-html@example.com")
                .subject("HTML富文本通知")
                .htmlContent("<h1 style='color:blue;'>尊敬的用户</h1><p>您的服务已开启！</p>")
                .build();

        mockMvc.perform(post("/sample/mail/basic/html-content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage msg = greenMail.getReceivedMessages()[0];
        assertThat(msg.getSubject()).isEqualTo("HTML富文本通知");
        assertThat(msg.getContent().toString()).contains("尊敬的用户");
    }

    @Test
    @DisplayName("3. BasicController 动态 HTML 模板发信：校验 Thymeleaf 模板解析与动态占位符注入")
    void shouldSendTemplateMailViaBasicController() throws Exception {
        UserVerificationMailRequest req = UserVerificationMailRequest.builder()
                .to("recipient-template@example.com")
                .subject("安全验证码")
                .username("阿腾")
                .verifyCode("686868")
                .build();

        mockMvc.perform(post("/sample/mail/basic/template")
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
    @DisplayName("4. BasicController 多语言国际化验证码模板端到端渲染验证")
    void shouldSendLocalizedTemplateEmailViaBasicController() throws Exception {
        UserVerificationMailRequest req = UserVerificationMailRequest.builder()
                .to("international-user@example.com")
                .username("JohnDoe")
                .verifyCode("998877")
                .build();

        mockMvc.perform(post("/sample/mail/basic/i18n-template")
                        .param("language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        String body = GreenMailUtil.getBody(messages[0]);
        assertThat(body).contains("Security Verification Code");
        assertThat(body).contains("JohnDoe");
        assertThat(body).contains("998877");
    }

    // ==========================================
    // 2. MailMediaController 测试
    // ==========================================

    @Test
    @DisplayName("5. MediaController 附件流式装配发信：校验 RFC 6266 大附件挂载与 MIME 结构解析")
    void shouldSendAttachmentMailViaMediaController() throws Exception {
        AttachmentMailRequest req = AttachmentMailRequest.builder()
                .to("recipient-attach@example.com")
                .subject("十月对账单与合同附件")
                .content("请查收附件中的对账单PDF")
                .fileName("十月对账单.pdf")
                .build();

        mockMvc.perform(post("/sample/mail/media/attachment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);

        MimeMessage msg = messages[0];
        assertThat(msg.getContent()).isInstanceOf(Multipart.class);
        Multipart multipart = (Multipart) msg.getContent();

        boolean foundAttachment = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            String disposition = bodyPart.getDisposition();
            if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                String decodedName = MimeUtility.decodeText(bodyPart.getFileName());
                if ("十月对账单.pdf".equals(decodedName)) {
                    foundAttachment = true;
                }
            }
        }
        assertThat(foundAttachment).isTrue();
    }

    @Test
    @DisplayName("6. MediaController 正交内联资源 (Inline CID) 图文混排发信验证")
    void shouldSendInlineMediaViaMediaController() throws Exception {
        InlineMediaMailRequest req = InlineMediaMailRequest.builder()
                .to("recipient-inline@example.com")
                .subject("品牌升级通知")
                .title("全新企业标识发布")
                .build();

        mockMvc.perform(post("/sample/mail/media/inline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage msg = messages[0];
        assertThat(msg.getContent()).isInstanceOf(Multipart.class);
    }

    // ==========================================
    // 3. MailAccountController 测试
    // ==========================================

    @Test
    @DisplayName("7. AccountController 多账号指定通道发信：切换运维 ops 通道校验发信人与隔离")
    void shouldSendOpsMailViaAccountController() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("recipient-ops@example.com")
                .subject("CPU 使用率超阈值告警")
                .content("服务器集群 CPU 使用率超过 90%，请紧急排查！")
                .build();

        mockMvc.perform(post("/sample/mail/account/ops-channel")
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
    @DisplayName("8. AccountController 外部数据库动态发信账号供给 (MailAccountProvider) 懒加载与 LRU 缓存复用实测")
    void shouldSendMailViaDynamicProviderFromDatabaseAndCacheSender() throws Exception {
        // 1. 模拟在数据库中配置并加密保存动态账号
        databaseMailAccountProvider.saveAccount(
                "db-vip",
                "127.0.0.1",
                smtpPort,
                "default@example.com",
                "secret-password-placeholder",
                false,
                "vip-center@example.com",
                "阿腾VIP服务中心"
        );

        DynamicAccountMailRequest req = DynamicAccountMailRequest.builder()
                .account("db-vip")
                .to("client-vip@example.com")
                .subject("VIP尊享权益推介")
                .content("尊敬的贵宾客户，您的专属权益已就绪")
                .build();

        // 2. 首次通过 Controller 发信，触发数据库懒加载
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1);
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages1 = greenMail.getReceivedMessages();
        assertThat(messages1).hasSize(1);
        assertThat(messages1[0].getSubject()).isEqualTo("VIP尊享权益推介");
        assertThat(messages1[0].getFrom()[0].toString()).contains("vip-center@example.com");

        greenMail.purgeEmailFromAllMailboxes();

        // 3. 二次发信，验证命中受管 LRU 缓存，不再重复查库
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1); // 保持为 1
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
    }

    @Test
    @DisplayName("9. AccountController 双轨防张冠李戴防线：显式指定未命中外部数据库账号强制快速失败，严禁错发默认系统邮箱")
    void shouldFailFastWhenExplicitAccountNotFoundPreventingWrongDelivery() throws Exception {
        DynamicAccountMailRequest req = DynamicAccountMailRequest.builder()
                .account("non-existent-secret-account")
                .to("leak-target@example.com")
                .subject("绝密内部审计报告")
                .content("敏感数据严禁外泄")
                .build();

        // 显式指定账号不存在时，必须抛出受控业务异常并在响应中返回 40002 错误码快速失败，严禁静默降级到默认邮箱
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(MailResultCode.MAIL_ACCOUNT_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("未找到指定的发信账号")));

        // 确保没有任何邮件发出
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("10. AccountController 门面注销 API (evictDynamicAccount) 驱逐缓存后再次发信重新触发懒加载")
    void shouldEvictDynamicAccountViaFacadeAndReloadFromDatabase() throws Exception {
        databaseMailAccountProvider.saveAccount(
                "db-sales",
                "127.0.0.1",
                smtpPort,
                "default@example.com",
                "secret-password-placeholder",
                false,
                "sales@example.com",
                "销售业务中心"
        );

        DynamicAccountMailRequest req = DynamicAccountMailRequest.builder()
                .account("db-sales")
                .to("customer@example.com")
                .subject("商机通知")
                .content("最新产品方案")
                .build();

        // 1. 首次发信，加载缓存
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1);

        // 2. 调用驱逐接口注销 db-sales
        mockMvc.perform(post("/sample/mail/account/evict-cache")
                        .param("accountName", "db-sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        greenMail.purgeEmailFromAllMailboxes();

        // 3. 再次发信，验证重新从数据库懒加载
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("11. AccountController 领域事件 (MailAccountEvictEvent) 广播驱动动态账号热驱逐")
    void shouldEvictDynamicAccountViaEventBroadcast() throws Exception {
        databaseMailAccountProvider.saveAccount(
                "db-mkt",
                "127.0.0.1",
                smtpPort,
                "default@example.com",
                "secret-password-placeholder",
                false,
                "mkt@example.com",
                "市场营销部"
        );

        DynamicAccountMailRequest req = DynamicAccountMailRequest.builder()
                .account("db-mkt")
                .to("subscriber@example.com")
                .subject("优惠活动")
                .content("双十一大促开启")
                .build();

        // 1. 首次发信
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1);

        // 2. 模拟分布式总线或本地发布 MailAccountEvictEvent 广播
        mockMvc.perform(post("/sample/mail/account/broadcast-evict")
                        .param("accountName", "db-mkt"))
                .andExpect(status().isOk());

        greenMail.purgeEmailFromAllMailboxes();

        // 3. 再次发信，重新触发数据库加载
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("12. AccountController 复用物理 Sender 前提下依据 message.from/fromName 动态覆盖发件人别名")
    void shouldOverrideSenderAliasDynamically() throws Exception {
        DynamicAccountMailRequest req = DynamicAccountMailRequest.builder()
                .account("default")
                .to("alias-target@example.com")
                .subject("别名动态覆盖测试")
                .content("测试动态自定义 From 身份")
                .from("custom-support@example.com")
                .fromName("专属客户经理")
                .build();

        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        jakarta.mail.Address[] from = messages[0].getFrom();
        assertThat(from).hasSize(1);
        jakarta.mail.internet.InternetAddress addr = (jakarta.mail.internet.InternetAddress) from[0];
        assertThat(addr.getAddress()).isEqualTo("custom-support@example.com");
        assertThat(addr.getPersonal()).isEqualTo("专属客户经理");
    }

    @Test
    @DisplayName("13. AccountController 管理端 SMTP 物理连通性一键测试探针端到端接口自验")
    void shouldTestConnectionViaAccountController() throws Exception {
        // 1. 存在的默认账号探针返回成功
        mockMvc.perform(post("/sample/mail/account/test-connection")
                        .param("accountName", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 存在的 ops 账号探针返回成功
        mockMvc.perform(post("/sample/mail/account/test-connection")
                        .param("accountName", "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 3. 不存在的账号探测触发语义化异常拦截 (40002)
        mockMvc.perform(post("/sample/mail/account/test-connection")
                        .param("accountName", "non-existent-probe-acc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    // ==========================================
    // 4. MailReliableController 测试
    // ==========================================

    @Test
    @DisplayName("14. ReliableController 批量个性化发送员工工资条端到端自验并验证聚合回执与物理送达")
    void shouldSendBatchPersonalizedEmailsSuccessfully() throws Exception {
        SalarySlipBatchRequest batchRequest = SalarySlipBatchRequest.builder()
                .items(new ValidList<>(List.of(
                        SalarySlipItemRequest.builder()
                                .email("emp1@example.com")
                                .employeeName("张三")
                                .salaryPeriod("2026年10月")
                                .netSalary(new BigDecimal("15000.00"))
                                .build(),
                        SalarySlipItemRequest.builder()
                                .email("emp2@example.com")
                                .employeeName("李四")
                                .salaryPeriod("2026年10月")
                                .netSalary(new BigDecimal("18500.50"))
                                .build(),
                        SalarySlipItemRequest.builder()
                                .email("emp3@example.com")
                                .employeeName("王五")
                                .salaryPeriod("2026年10月")
                                .netSalary(new BigDecimal("22000.00"))
                                .build()
                )))
                .build();

        mockMvc.perform(post("/sample/mail/reliable/batch-salary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.successCount").value(3))
                .andExpect(jsonPath("$.data.failureCount").value(0));

        assertThat(greenMail.waitForIncomingEmail(5000, 3)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(3);
    }

    @Test
    @DisplayName("15. ReliableController 受管异步发信：校验 CompletableFuture 异步处理与异步事件触发")
    void shouldSendAsyncMailViaReliableController() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("recipient-async@example.com")
                .subject("异步通知任务")
                .content("异步并发发信验证")
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/sample/mail/reliable/async-send")
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
    @DisplayName("16. ReliableController 本地事务感知发信（sendAfterCommit）：物理事务提交成功后正常发出邮件")
    void shouldSendTransactionCommitViaReliableController() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("tx-success@example.com")
                .subject("用户注册成功通知")
                .content("恭喜您的账号注册成功！")
                .build();

        mockMvc.perform(post("/sample/mail/reliable/transaction-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("成功触发")));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).isEqualTo("用户注册成功通知");
    }

    @Test
    @DisplayName("17. ReliableController 本地事务感知发信（sendAfterCommit）：事务异常回滚时自动取消防幽灵邮件")
    void shouldSendTransactionRollbackViaReliableController() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("tx-rollback@example.com")
                .subject("幽灵邮件测试")
                .content("若数据库回滚，这封邮件严禁发出！")
                .build();

        mockMvc.perform(post("/sample/mail/reliable/transaction-rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("成功阻止幽灵邮件外发")));

        // 确保物理服务器未收到任何邮件
        assertThat(greenMail.waitForIncomingEmail(1000, 1)).isFalse();
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("18. ReliableController 紧急告警邮件高优先级标头、阅读回执与合规密送端到端全真验证")
    void shouldSendUrgentAlertViaReliableController() throws Exception {
        SystemAlertMailRequest req = SystemAlertMailRequest.builder()
                .to("ops-team@example.com")
                .serviceName("order-service")
                .alertLevel("P0-CRITICAL")
                .errorMessage("主库 CPU 飙高至 100%，连接池耗尽！")
                .requestReadReceipt(true)
                .archiveBcc("audit-compliance@atengk.com")
                .build();

        mockMvc.perform(post("/sample/mail/reliable/urgent-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 1 To + 1 Bcc = 2 份副本
        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(2);

        MimeMessage alertMsg = messages[0];
        assertThat(alertMsg.getHeader("X-Priority", null)).isEqualTo("1");
        assertThat(alertMsg.getHeader("Importance", null)).isEqualTo("High");
        assertThat(alertMsg.getHeader("X-MSMail-Priority", null)).isEqualTo("High");
        assertThat(alertMsg.getHeader("Disposition-Notification-To", null)).isEqualTo("default@example.com");
    }

    // ==========================================
    // 5. 补充底层 Service 边界自测
    // ==========================================

    @Test
    @DisplayName("19. 拦截器黑名单阻断防卫：收件人触碰安全黑名单时阻断发信并触发失败事件")
    void shouldBlockMailWhenRecipientInBlacklist() {
        assertThatThrownBy(() -> mailScenarioService.sendSimpleText(
                SampleAuditMailInterceptor.BLOCKED_EMAIL,
                "尝试发送给黑名单",
                "该邮件应被拦截"
        )).isInstanceOf(MailSendException.class)
                .hasMessageContaining("邮件发送被拦截器阻断");

        assertThat(greenMail.getReceivedMessages()).isEmpty();
        assertThat(eventListener.getFailedCount().get()).isEqualTo(1);
        assertThat(failureHandler.getFailureCount().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("20. 编程式动态账号发信：演示 DynamicMailSession 与 LRU 物理 Sender 复用")
    void shouldSendMailViaDynamicAccountSessionDirectly() throws Exception {
        MailAccount account = MailAccount.builder()
                .name("dynamic-tenant-session")
                .host("127.0.0.1")
                .port(smtpPort)
                .username("default@example.com")
                .password("secret-password-placeholder")
                .ssl(false)
                .defaultFrom("dynamic-sender@example.com")
                .defaultFromName("动态租户客服")
                .build();

        MailSendResult result = mailScenarioService.sendWithDynamicAccount(
                account,
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

    @Test
    @DisplayName("21. ReliableController 紧急告警接口触发 @InEnum 格式校验第一道防线快速失败")
    void shouldFailWhenAlertLevelIsInvalidInUrgentAlert() throws Exception {
        SystemAlertMailRequest req = SystemAlertMailRequest.builder()
                .to("oncall@example.com")
                .serviceName("order-service")
                .alertLevel("INVALID_LEVEL")
                .errorMessage("测试错误")
                .build();

        mockMvc.perform(post("/sample/mail/reliable/urgent-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("告警级别不合法")));
    }

    // ==========================================
    // 五、MailGovernanceController 高阶治理、容灾、沙箱与可观测闭环测试
    // ==========================================

    @Test
    @DisplayName("22. GovernanceController 频控演示触发 MAIL_RATE_LIMITED(40007) 并验证窗口重置恢复")
    void shouldTriggerRateLimitAndRecoverInGovernanceController() throws Exception {
        OutboundGuardDemoRequest req = OutboundGuardDemoRequest.builder()
                .to("rate-demo@example.com")
                .subject("频控测试主题")
                .content("频控测试正文")
                .burstCount(2)
                .build();

        mockMvc.perform(post("/sample/mail/governance/rate-limit-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowedCount").value(1))
                .andExpect(jsonPath("$.data.blockedCount").value(1))
                .andExpect(jsonPath("$.data.triggeredResultCode").value(MailResultCode.MAIL_RATE_LIMITED.getCode()))
                .andExpect(jsonPath("$.data.recoveredAfterResetOrRelease").value(true));

        assertThat(greenMail.waitForIncomingEmail(3000, 2)).isTrue();
    }

    @Test
    @DisplayName("23. GovernanceController 日配额硬熔断触发 MAIL_QUOTA_EXCEEDED(40008) 并捕获领域事件")
    void shouldTriggerQuotaBreakerAndCaptureEvent() throws Exception {
        OutboundGuardDemoRequest req = OutboundGuardDemoRequest.builder()
                .to("quota-demo@example.com")
                .subject("日配额测试")
                .content("日配额测试正文")
                .burstCount(2)
                .build();

        mockMvc.perform(post("/sample/mail/governance/quota-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowedCount").value(1))
                .andExpect(jsonPath("$.data.blockedCount").value(1))
                .andExpect(jsonPath("$.data.triggeredResultCode").value(MailResultCode.MAIL_QUOTA_EXCEEDED.getCode()))
                .andExpect(jsonPath("$.data.quotaEventCaptured").value(true));

        assertThat(eventListener.getQuotaExceededCount().get()).isGreaterThanOrEqualTo(1);
        assertThat(eventListener.getLastQuotaExceededEvent()).isNotNull();
    }

    @Test
    @DisplayName("24. GovernanceController bizId 幂等防重触发 MAIL_IDEMPOTENT_REJECTED(40009) 且失败放锁可重试")
    void shouldEnforceIdempotentGuardAndReleaseOnFailure() throws Exception {
        OutboundGuardDemoRequest req = OutboundGuardDemoRequest.builder()
                .to("idem-demo@example.com")
                .subject("幂等演示主题")
                .content("幂等演示正文")
                .bizId("ORDER-PAY-1017")
                .burstCount(2)
                .build();

        mockMvc.perform(post("/sample/mail/governance/idempotent-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allowedCount").value(1))
                .andExpect(jsonPath("$.data.blockedCount").value(1))
                .andExpect(jsonPath("$.data.triggeredResultCode").value(MailResultCode.MAIL_IDEMPOTENT_REJECTED.getCode()))
                .andExpect(jsonPath("$.data.recoveredAfterResetOrRelease").value(true));

        assertThat(greenMail.waitForIncomingEmail(3000, 2)).isTrue();
    }

    @Test
    @DisplayName("25. GovernanceController 主备顺延容灾自动切换 ops 并保留原 From 于 Reply-To")
    void shouldFailoverToBackupAccountAndPreserveReplyTo() throws Exception {
        FailoverDemoRequest req = FailoverDemoRequest.builder()
                .to("failover-user@example.com")
                .subject("主备容灾切换测试")
                .content("主通道关闭后应顺延至 ops 通道送达")
                .primaryFrom("primary-support@corp.example.com")
                .build();

        mockMvc.perform(post("/sample/mail/governance/failover-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.messageId").isNotEmpty());

        assertThat(greenMail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage delivered = greenMail.getReceivedMessages()[0];
        assertThat(((InternetAddress) delivered.getFrom()[0]).getAddress()).isEqualTo("ops@example.com");
        assertThat(delivered.getReplyTo()).isNotEmpty();
        assertThat(((InternetAddress) delivered.getReplyTo()[0]).getAddress())
                .isEqualTo("primary-support@corp.example.com");
    }

    @Test
    @DisplayName("26. GovernanceController sendBatchAfterCommit 提交成功投递且回滚安全取消整批幽灵邮件")
    void shouldCommitOrCancelSendBatchAfterCommit() throws Exception {
        ValidList<SimpleTextMailRequest> items = new ValidList<>(List.of(
                SimpleTextMailRequest.builder().to("batch1@example.com").subject("批量一").content("内容一").build(),
                SimpleTextMailRequest.builder().to("batch2@example.com").subject("批量二").content("内容二").build()
        ));

        BatchAfterCommitRequest rollbackReq = BatchAfterCommitRequest.builder()
                .items(items)
                .shouldRollback(true)
                .build();

        mockMvc.perform(post("/sample/mail/governance/batch-after-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("回滚")));

        assertThat(greenMail.waitForIncomingEmail(400, 1)).isFalse();
        assertThat(greenMail.getReceivedMessages()).isEmpty();

        BatchAfterCommitRequest commitReq = BatchAfterCommitRequest.builder()
                .items(items)
                .shouldRollback(false)
                .build();

        mockMvc.perform(post("/sample/mail/governance/batch-after-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("提交")));

        assertThat(greenMail.waitForIncomingEmail(3000, 2)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
    }

    @Test
    @DisplayName("27. GovernanceController 沙箱双策略：非白名单重定向并清空 Cc，logOnly 不发起物理连接")
    void shouldRedirectSandboxAndSupportLogOnlyMode() throws Exception {
        SandboxDemoRequest redirectReq = SandboxDemoRequest.builder()
                .to("real-vip@customer.example.com")
                .cc("secret-cc@customer.example.com")
                .redirectTo("qa-inbox@internal.example.com")
                .allowedRecipients(List.of("*@internal.example.com"))
                .subject("账单测试邮件")
                .content("验证沙箱拦截重定向与清空抄送人")
                .logOnly(false)
                .build();

        mockMvc.perform(post("/sample/mail/governance/sandbox-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(redirectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage redirected = messages[0];
        assertThat(redirected.getRecipients(Message.RecipientType.TO))
                .extracting(addr -> ((InternetAddress) addr).getAddress())
                .containsExactly("qa-inbox@internal.example.com");
        assertThat(redirected.getRecipients(Message.RecipientType.CC)).isNullOrEmpty();
        assertThat(redirected.getSubject()).startsWith("[SANDBOX-REDIRECT");
        assertThat(redirected.getHeader("X-Sandbox-Redirected-From"))
                .anySatisfy(h -> assertThat(h).contains("real-vip@customer.example.com"));

        greenMail.purgeEmailFromAllMailboxes();

        SandboxDemoRequest logOnlyReq = SandboxDemoRequest.builder()
                .to("real-vip@customer.example.com")
                .subject("纯日志模拟测试")
                .content("logOnly 模式不应发起物理发送")
                .logOnly(true)
                .build();

        mockMvc.perform(post("/sample/mail/governance/sandbox-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logOnlyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.messageId").value(containsString("<sandbox-log-")));

        assertThat(greenMail.waitForIncomingEmail(300, 1)).isFalse();
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("28. GovernanceController Freemarker 模板渲染与 List-Unsubscribe / Return-Path 合规头透传")
    void shouldRenderFreemarkerTemplateAndAttachComplianceHeaders() throws Exception {
        ComplianceFreemarkerRequest req = ComplianceFreemarkerRequest.builder()
                .to("subscriber@example.com")
                .username("张运维")
                .noticeTitle("安全合规周报通知")
                .returnPath("bounces@mailer.example.com")
                .listUnsubscribe("<https://unsubscribe.example.com/u/1017>, <mailto:unsub@example.com>")
                .listUnsubscribePost("List-Unsubscribe=One-Click")
                .build();

        mockMvc.perform(post("/sample/mail/governance/freemarker-compliance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage delivered = greenMail.getReceivedMessages()[0];
        String body = delivered.getContent().toString();
        assertThat(body).contains("张运维").contains("安全合规周报通知").contains("RFC 2369");
        assertThat(delivered.getHeader("List-Unsubscribe"))
                .containsExactly("<https://unsubscribe.example.com/u/1017>, <mailto:unsub@example.com>");
        assertThat(delivered.getHeader("List-Unsubscribe-Post"))
                .containsExactly("List-Unsubscribe=One-Click");
        assertThat(delivered.getHeader("Return-Path"))
                .anySatisfy(rp -> assertThat(rp).contains("bounces@mailer.example.com"));
    }

    @Test
    @DisplayName("29. GovernanceController TenantContextHolder 多租户动态路由与 clearDynamicAccounts 缓存清理")
    void shouldRouteViaTenantContextAndClearDynamicAccounts() throws Exception {
        assertThat(databaseMailAccountProvider).isNotNull();
        databaseMailAccountProvider.saveAccount(
                "tenant-1017",
                "127.0.0.1",
                smtpPort,
                "",
                "",
                false,
                "tenant-1017@example.com",
                "Tenant 1017");
        databaseMailAccountProvider.resetQueryCount();

        try {
            TenantRouteMailRequest req = TenantRouteMailRequest.builder()
                    .tenantId("tenant-1017")
                    .to("tenant-user@example.com")
                    .subject("多租户动态路由通知")
                    .content("通过 TenantContextHolder 自动切换租户账号发送")
                    .build();

            mockMvc.perform(post("/sample/mail/governance/tenant-route")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.success").value(true));

            assertThat(greenMail.waitForIncomingEmail(3000, 1)).isTrue();
            MimeMessage routedMessage = greenMail.getReceivedMessages()[0];
            assertThat(((InternetAddress) routedMessage.getFrom()[0]).getAddress())
                    .isEqualTo("tenant-1017@example.com");

            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1);

            // 命中动态账号缓存，不重复查库
            mockMvc.perform(post("/sample/mail/governance/tenant-route")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
            assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(1);

            // 调用 clearDynamicAccounts 清空全量缓存后再次发送，重新触发 Provider 查库
            mockMvc.perform(post("/sample/mail/governance/clear-dynamic-accounts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(post("/sample/mail/governance/tenant-route")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
            assertThat(databaseMailAccountProvider.getQueryCount()).isEqualTo(2);
        } finally {
            databaseMailAccountProvider.deleteAccount("tenant-1017");
        }
    }

    @Test
    @DisplayName("30. GovernanceController sendAsyncAfterCommit 事务提交异步外发与回滚取消")
    void shouldCommitOrCancelSendAsyncAfterCommit() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("async-tx@example.com")
                .subject("异步事务感知通知")
                .content("仅在事务提交后由受管线程池异步投递")
                .build();

        mockMvc.perform(post("/sample/mail/governance/async-after-commit")
                        .param("shouldRollback", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("回滚")));

        assertThat(greenMail.waitForIncomingEmail(400, 1)).isFalse();

        mockMvc.perform(post("/sample/mail/governance/async-after-commit")
                        .param("shouldRollback", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("提交")));

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
    }

    @Test
    @DisplayName("31. GovernanceController 函数式 DSL send(Consumer<MailMessage>) 端到端发信成功")
    void shouldSendMailViaFunctionalDsl() throws Exception {
        SimpleTextMailRequest req = SimpleTextMailRequest.builder()
                .to("dsl-user@example.com")
                .subject("函数式 DSL 邮件")
                .content("通过 MailTemplate.send(Consumer<MailMessage>) 发信")
                .build();

        mockMvc.perform(post("/sample/mail/governance/dsl-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        assertThat(greenMail.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage delivered = greenMail.getReceivedMessages()[0];
        assertThat(delivered.getSubject()).isEqualTo("函数式 DSL 邮件");
    }

    @Test
    @DisplayName("32. DTO 校验补全回归：SalarySlipBatch @NotEmpty / DynamicAccount from @Email / Failover primaryFrom @Email")
    void shouldRejectInvalidDtoFieldsOnPatchedControllers() throws Exception {
        SalarySlipBatchRequest emptyBatch = SalarySlipBatchRequest.builder()
                .items(new ValidList<>(List.of()))
                .build();
        mockMvc.perform(post("/sample/mail/reliable/batch-salary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyBatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("工资条列表不能为空")));

        DynamicAccountMailRequest invalidFrom = DynamicAccountMailRequest.builder()
                .account("dyn-bad-from")
                .from("not-an-email")
                .to("user@example.com")
                .subject("无效发件人测试")
                .content("正文")
                .build();
        mockMvc.perform(post("/sample/mail/account/dynamic-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidFrom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("发件人电子邮箱格式不合法")));

        FailoverDemoRequest invalidPrimaryFrom = FailoverDemoRequest.builder()
                .to("user@example.com")
                .subject("测试")
                .content("正文")
                .primaryFrom("broken-email-format")
                .build();
        mockMvc.perform(post("/sample/mail/governance/failover-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPrimaryFrom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("主通道发件人电子邮箱格式不合法")));

        SystemAlertMailRequest invalidArchiveBcc = SystemAlertMailRequest.builder()
                .to("oncall@example.com")
                .serviceName("order-service")
                .alertLevel("P0")
                .errorMessage("测试告警")
                .archiveBcc("invalid-bcc-email")
                .build();
        mockMvc.perform(post("/sample/mail/reliable/urgent-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidArchiveBcc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("合规审计归档密送邮箱格式不合法")));
    }

    @Test
    @DisplayName("33. 可观测闭环：MailSafeHealthIndicator、Micrometer 指标快照及 Actuator /health 与 /prometheus")
    void shouldExposeHealthIndicatorAndPrometheusMetrics() throws Exception {
        // 先发起一次发信确保 Micrometer Counter 已注册并产生计数
        SimpleTextMailRequest warmup = SimpleTextMailRequest.builder()
                .to("metrics-warmup@example.com")
                .subject("指标预热邮件")
                .content("确保 ateng_mail_send_count 已采集")
                .build();
        mockMvc.perform(post("/sample/mail/governance/dsl-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(warmup)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sample/mail/governance/observability-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.healthStatus").value("UP"))
                .andExpect(jsonPath("$.data.staticAccountCount").value(2.0))
                .andExpect(jsonPath("$.data.totalSendCount").isNumber());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.mail.status").value("UP"));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("ateng_mail_send_count")));
    }
}
