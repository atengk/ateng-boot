package io.github.atengk.boot.sms.guard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.YunpianSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.repository.InMemorySmsVerifyCodeRepository;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.github.atengk.boot.sms.template.SmsVerifyCodeTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 工单 #1148 通知频控命名空间隔离与日志/审计敏感内容脱敏测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("工单 #1148 通知频控隔离与敏感内容脱敏测试")
class SmsNotifyRateLimitAndDesensitizeTest {

    private MockWebServer mockWebServer;

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("通知频控默认关闭向后兼容，开启后严格与验证码 verify: 隔离并支持 skipRateLimit/urgent 豁免")
    void shouldIsolateNotifyAndVerifyRateLimitNamespaces() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T08:00:00Z"), ZoneOffset.UTC);
        SmsProperties props = new SmsProperties();
        props.getRateLimit().setPerMinute(1);
        props.getRateLimit().setPerHour(10);
        props.getRateLimit().setPerDay(20);
        props.getVerifyCode().setResendInterval(Duration.ZERO);

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder().name("mock").supplier(SmsSupplier.MOCK).defaultTemplateId("TPL_1").build());

        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());
        InMemorySmsRateLimiter rateLimiter = new InMemorySmsRateLimiter(props.getRateLimit(), 100, clock);

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider),
                registry,
                null, null, null, null, null, null, null, null, null, clock, null, rateLimiter
        );
        SmsVerifyCodeTemplate verifyTemplate = new SmsVerifyCodeTemplate(
                template, props, rateLimiter, new InMemorySmsVerifyCodeRepository(100, clock)
        );

        // 1. 默认关闭 notifyRateLimitEnabled 时连续发送通知不报错
        assertThat(props.getGuard().isNotifyRateLimitEnabled()).isFalse();
        template.send(SmsRequest.of("13800138000", "TPL_NOTIFY"));
        template.send(SmsRequest.of("13800138000", "TPL_NOTIFY"));
        assertThat(mockProvider.getSentCount()).isEqualTo(2);

        // 2. 开启 notifyRateLimitEnabled 后，notify: 与 verify: 互不挤占
        props.getGuard().setNotifyRateLimitEnabled(true);
        String phoneA = "13800138001";
        assertThat(template.send(SmsRequest.of(phoneA, "TPL_NOTIFY")).isSuccess()).isTrue();
        assertThatThrownBy(() -> template.send(SmsRequest.of(phoneA, "TPL_NOTIFY")))
                .isInstanceOf(SmsRateLimitException.class);

        // 声明 skipRateLimit=true 或 urgent=true 正常放行
        assertThat(template.send(SmsRequest.of(phoneA, "TPL_NOTIFY").withSkipRateLimit(true)).isSuccess()).isTrue();
        assertThat(template.send(SmsRequest.of(phoneA, "TPL_NOTIFY").withUrgent(true)).isSuccess()).isTrue();

        // 同一号码发送验证码不受 notify: 耗尽影响
        String code = verifyTemplate.generateAndSend(phoneA, "login");
        assertThat(code).hasSize(6);

        // 3. 反向验证：同一号码验证码 verify: 耗尽后，通知仍可发送 1 条
        String phoneB = "13800138002";
        verifyTemplate.generateAndSend(phoneB, "login");
        assertThatThrownBy(() -> verifyTemplate.generateAndSend(phoneB, "register"))
                .isInstanceOf(SmsRateLimitException.class);
        assertThat(template.send(SmsRequest.of(phoneB, "TPL_NOTIFY")).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("开启 logDesensitizeContent 时日志与审计事件掩码遮罩，而 MockSmsProvider 与 MockWebServer 保持真明文")
    void shouldMaskLogsAndAuditWhileKeepingOutboundPlaintext() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"msg\":\"发送成功\",\"count\":1,\"fee\":0.05,\"sid\":123456789}"));

        SmsProperties props = new SmsProperties();
        assertThat(props.getGuard().isLogDesensitizeContent()).isTrue();

        SmsChannelRegistry registry = new SmsChannelRegistry(props);
        registry.register(SmsChannel.builder().name("mock").supplier(SmsSupplier.MOCK).defaultTemplateId("TPL_1").build());
        registry.register(SmsChannel.builder()
                .name("yunpian-real")
                .supplier(SmsSupplier.YUNPIAN)
                .accessKeyId("YP_AK")
                .endpoint(mockWebServer.url("/").toString())
                .build());

        MockSmsProvider mockProvider = new MockSmsProvider(props.getGuard());
        YunpianSmsProvider yunpianProvider = new YunpianSmsProvider(RestClient.builder());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        SmsTemplate template = new SmsTemplate(
                props,
                List.of(mockProvider, yunpianProvider),
                registry,
                null, null, null, null, null, null, null, publisher, null, null, null, null
        );

        Logger mockLogger = (Logger) LoggerFactory.getLogger(MockSmsProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        mockLogger.addAppender(appender);

        try {
            String rawCode = "889900";
            String rawPwd = "secret123";
            String rawContent = "【测试】您的验证码是889900，5分钟内有效";

            // 1. Mock 渠道测试日志与审计脱敏
            SmsRequest mockReq = SmsRequest.builder()
                    .phone("13812345678")
                    .templateId("TPL_VERIFY")
                    .param("code", rawCode)
                    .param("pwd", rawPwd)
                    .param("orderNo", "ORD-2026-01")
                    .content(rawContent)
                    .build();
            SmsResult mockResult = template.send(mockReq);
            assertThat(mockResult.isSuccess()).isTrue();

            // MockSmsProvider 内存中收到 100% 真明文
            SmsRequest recorded = mockProvider.getLastRequest();
            assertThat(recorded.getParams().asMap().get("code")).isEqualTo(rawCode);
            assertThat(recorded.getParams().asMap().get("pwd")).isEqualTo(rawPwd);
            assertThat(recorded.getContent()).isEqualTo(rawContent);

            // 日志不泄露验证码、密码及明文正文
            List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs).isNotEmpty();
            assertThat(logs).noneMatch(msg -> msg.contains(rawCode) || msg.contains(rawPwd));

            // 审计事件中敏感参数和纯文本正文已被脱敏，非敏感参数与手机号保持语义完整
            ArgumentCaptor<SmsSendAuditEvent> captor = ArgumentCaptor.forClass(SmsSendAuditEvent.class);
            verify(publisher, atLeastOnce()).publishEvent(captor.capture());
            SmsSendAuditEvent auditEvent = captor.getValue();
            assertThat(auditEvent.getRequest().getParams().asMap().get("code")).isEqualTo("******");
            assertThat(auditEvent.getRequest().getParams().asMap().get("pwd")).isEqualTo("******");
            assertThat(auditEvent.getRequest().getParams().asMap().get("orderNo")).isEqualTo("ORD-2026-01");
            assertThat(auditEvent.getRequest().getContent()).doesNotContain(rawCode);

            // 2. 批量审计脱敏校验
            template.sendBatch(BatchSmsRequest.ofContent(List.of("13800001111", "13800002222"), rawContent));
            verify(publisher, atLeastOnce()).publishEvent(captor.capture());
            SmsSendAuditEvent batchAudit = captor.getValue();
            assertThat(batchAudit.getBatchRequest().getContent()).doesNotContain(rawCode);

            // 3. MockWebServer 出站 HTTP 报文保持 100% 真明文
            SmsRequest httpReq = SmsRequest.builder()
                    .phone("13812345678")
                    .channel("yunpian-real")
                    .content(rawContent)
                    .build();
            SmsResult httpRes = template.send(httpReq);
            assertThat(httpRes.isSuccess()).isTrue();

            RecordedRequest recordedHttp = mockWebServer.takeRequest();
            String decodedBody = URLDecoder.decode(recordedHttp.getBody().readUtf8(), StandardCharsets.UTF_8);
            assertThat(decodedBody).contains("889900");
        } finally {
            mockLogger.detachAppender(appender);
        }
    }
}
