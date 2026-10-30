package io.github.atengk.boot.sample.mail;

import io.github.atengk.boot.mail.model.MailPriority;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地真实远程 SMTP 邮件服务联调测试（仅在配置了 application-local.yml 时执行）
 *
 * @author Ateng
 * @since 2026-10-30
 */
@ActiveProfiles("local")
@SpringBootTest
@DisplayName("本地真实远程 SMTP 邮件服务全真联调测试")
class SampleMailLocalRealIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SampleMailLocalRealIntegrationTest.class);

    static boolean isLocalConfigured() {
        return new ClassPathResource("application-local.yml").exists();
    }

    @Autowired
    private MailTemplate mailTemplate;

    @Autowired
    private MailScenarioService mailScenarioService;

    @Value("${ateng.boot.mail.accounts.default.from:}")
    private String targetEmail;

    @Test
    @EnabledIf("isLocalConfigured")
    @DisplayName("1. 真实 QQ 邮箱 SMTP 物理连通性与 AUTH 凭据探针校验")
    void testRealSmtpConnection() {
        log.info("开始对真实 QQ 邮箱进行物理连通性握手与鉴权探测...");
        boolean connected = mailTemplate.testConnection("default");
        assertThat(connected).isTrue();

        mailTemplate.testConnectionOrThrow("default");
        log.info("真实 QQ 邮箱 SMTP 物理握手与 AUTH 鉴权 100% 成功！");
    }

    @Test
    @EnabledIf("isLocalConfigured")
    @DisplayName("2. 真实外发纯文本邮件至 QQ 邮箱并强断言物理回执")
    void testRealSendSimpleText() {
        String myEmail = targetEmail;
        log.info("正在向真实 QQ 邮箱 [{}] 发送联调测试邮件...", myEmail);

        MailSendResult result = mailScenarioService.sendSimpleText(
                myEmail,
                "[Ateng Boot] 纯文本发信真实联调成功",
                "恭喜！您收到了来自 ateng-boot-mail 基础设施真实发出的纯文本测试邮件，发信链路、线程池与 SMTP 协议完全正常！"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessageId()).isNotBlank();
        log.info("真实邮件发送成功！Message-ID: [{}], 耗时: {} ms",
                result.getMessageId(), result.getDuration().toMillis());
    }

    @Test
    @EnabledIf("isLocalConfigured")
    @DisplayName("3. 真实外发带 Thymeleaf 动态模板的国际化邮件")
    void testRealSendHtmlTemplate() {
        String myEmail = targetEmail;
        log.info("正在向真实 QQ 邮箱 [{}] 发送动态模板渲染邮件...", myEmail);

        MailSendResult result = mailScenarioService.sendLocalizedVerifyCode(
                myEmail,
                "Ateng 开发者",
                "668899",
                Locale.SIMPLIFIED_CHINESE
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessageId()).isNotBlank();
        log.info("真实模板邮件发送成功！Message-ID: [{}]", result.getMessageId());
    }

    @Test
    @EnabledIf("isLocalConfigured")
    @DisplayName("4. 真实外发高优先级告警邮件（携带 X-Priority、Importance 与阅读回执标头）")
    void testRealSendHighPriorityAlert() {
        String myEmail = targetEmail;
        log.info("正在向真实 QQ 邮箱 [{}] 发送高优先级告警邮件...", myEmail);

        MailSendResult result = mailScenarioService.sendUrgentAlert(
                myEmail,
                "[Ateng Boot] 线上系统高优先级告警验证",
                "这是一封具备强类型 MailPriority.HIGH 的重要邮件，客户端（如 QQ 邮箱、Foxmail）将展示紧急红旗标记！",
                MailPriority.HIGH,
                true,
                null
        );

        assertThat(result.isSuccess()).isTrue();
        log.info("真实高优先级告警邮件发送成功！Message-ID: [{}]", result.getMessageId());
    }
}
