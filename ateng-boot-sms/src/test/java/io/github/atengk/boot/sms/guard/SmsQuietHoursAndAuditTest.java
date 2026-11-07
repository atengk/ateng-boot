package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsIdempotentException;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 夜间免打扰、出站幂等防重与全链路发信审计事件单测
 *
 * @author Ateng
 * @since 2026-11-07
 */
@ExtendWith(MockitoExtension.class)
class SmsQuietHoursAndAuditTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("SmsTemplate: 夜间免打扰时段计算逻辑（跨午夜与日间窗口）")
    void testQuietHoursCalculation() {
        SmsProperties properties = new SmsProperties();
        properties.getGuard().setQuietHoursEnabled(true);
        properties.getGuard().setQuietHoursStart("22:00");
        properties.getGuard().setQuietHoursEnd("08:00");

        SmsTemplate template = new SmsTemplate(
                properties,
                List.of(new MockSmsProvider()),
                new SmsChannelRegistry(properties),
                null, null, null, null, null, null, null, null, null
        );

        // 跨午夜判定: 22:00 ~ 08:00
        assertThat(template.isInQuietHours(LocalTime.of(22, 0))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(23, 30))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(0, 0))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(5, 0))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(7, 59, 59))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(8, 0))).isFalse();
        assertThat(template.isInQuietHours(LocalTime.of(12, 0))).isFalse();
        assertThat(template.isInQuietHours(LocalTime.of(21, 59))).isFalse();

        // 当日区间判定: 12:00 ~ 14:00
        properties.getGuard().setQuietHoursStart("12:00");
        properties.getGuard().setQuietHoursEnd("14:00");
        assertThat(template.isInQuietHours(LocalTime.of(12, 0))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(13, 0))).isTrue();
        assertThat(template.isInQuietHours(LocalTime.of(14, 0))).isFalse();
        assertThat(template.isInQuietHours(LocalTime.of(15, 0))).isFalse();
    }

    @Test
    @DisplayName("SmsTemplate: 夜间免打扰静默拦截与紧急短信 (urgent) 豁免穿透")
    void testQuietHoursInterceptionAndUrgentBypass() {
        SmsProperties properties = new SmsProperties();
        // 设置当前全天为免打扰窗口
        properties.getGuard().setQuietHoursEnabled(true);
        properties.getGuard().setQuietHoursStart("00:00");
        properties.getGuard().setQuietHoursEnd("23:59:59");

        SmsChannelProperties channelProps = new SmsChannelProperties();
        channelProps.setSupplier(SmsSupplier.MOCK);
        channelProps.setDefaultTemplateId("TPL_QUIET");
        properties.getChannels().put("mock", channelProps);

        SmsTemplate template = new SmsTemplate(
                properties,
                List.of(new MockSmsProvider()),
                new SmsChannelRegistry(properties),
                null, null, null, null, null, null, null, eventPublisher, null
        );

        // 1. 普通非紧急短信在免打扰时段被前置静默拦截
        SmsRequest normalRequest = SmsRequest.builder()
                .phone("13800138000")
                .templateId("TPL_QUIET")
                .urgent(false)
                .build();

        SmsResult blockedResult = template.send(normalRequest);
        assertThat(blockedResult.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.BLOCKED);
        assertThat(blockedResult.isSuccess()).isFalse();
        assertThat(blockedResult.getRawMessage()).contains("夜间免打扰");

        // 验证广播了审计事件且标记为 blocked
        ArgumentCaptor<SmsSendAuditEvent> auditCaptor = ArgumentCaptor.forClass(SmsSendAuditEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(auditCaptor.capture());
        SmsSendAuditEvent blockedEvent = auditCaptor.getValue();
        assertThat(blockedEvent.isBlocked()).isTrue();
        assertThat(blockedEvent.isSuccess()).isFalse();

        // 2. 紧急短信 (urgent=true) 自动豁免穿透夜间免打扰
        SmsRequest urgentRequest = normalRequest.withUrgent(true);
        SmsResult urgentResult = template.send(urgentRequest);
        assertThat(urgentResult.getDeliveryStatus()).isEqualTo(SmsDeliveryStatus.MOCKED);
        assertThat(urgentResult.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("SmsTemplate: 出站幂等防重拦截与失败释放锁闭环")
    void testIdempotentGuardInterceptionAndLockRelease() {
        SmsProperties properties = new SmsProperties();
        properties.getGuard().setIdempotentEnabled(true);
        properties.getGuard().setIdempotentWindow(Duration.ofMinutes(3));

        SmsChannelProperties channelProps = new SmsChannelProperties();
        channelProps.setSupplier(SmsSupplier.MOCK);
        channelProps.setDefaultTemplateId("TPL_IDEM");
        properties.getChannels().put("mock", channelProps);

        InMemorySmsIdempotentGuard idempotentGuard = new InMemorySmsIdempotentGuard();
        SmsTemplate template = new SmsTemplate(
                properties,
                List.of(new MockSmsProvider()),
                new SmsChannelRegistry(properties),
                null, null, null, null, null, null, idempotentGuard, eventPublisher, null
        );

        SmsRequest request = SmsRequest.builder()
                .phone("13900139000")
                .templateId("TPL_IDEM")
                .params(SmsParam.of("code", "8888"))
                .build();

        // 首次发信成功
        SmsResult res1 = template.send(request);
        assertThat(res1.isSuccess()).isTrue();

        // 紧接着发信相同内容 -> 触发幂等防重异常
        assertThatThrownBy(() -> template.send(request))
                .isInstanceOf(SmsIdempotentException.class)
                .hasMessageContaining("幂等窗口");

        // 手动释放锁后，允许重试
        String key = idempotentGuard.generateKey(request);
        idempotentGuard.releaseLock(key);

        SmsResult res3 = template.send(request);
        assertThat(res3.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("SmsSendAuditEvent: 领域事件数据完整性与耗时度量")
    void testAuditEventAttributes() {
        SmsProperties properties = new SmsProperties();
        SmsChannelProperties channelProps = new SmsChannelProperties();
        channelProps.setSupplier(SmsSupplier.MOCK);
        channelProps.setDefaultTemplateId("TPL_AUDIT");
        properties.getChannels().put("mock", channelProps);

        SmsTemplate template = new SmsTemplate(
                properties,
                List.of(new MockSmsProvider()),
                new SmsChannelRegistry(properties),
                null, null, null, null, null, null, null, eventPublisher, null
        );

        SmsRequest request = SmsRequest.builder()
                .phone("13500135000")
                .templateId("TPL_AUDIT")
                .build();

        template.send(request);

        ArgumentCaptor<SmsSendAuditEvent> captor = ArgumentCaptor.forClass(SmsSendAuditEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        SmsSendAuditEvent event = captor.getValue();
        assertThat(event.getPhone()).isEqualTo("13500135000");
        assertThat(event.getPhones()).containsExactly("13500135000");
        assertThat(event.getTemplateId()).isEqualTo("TPL_AUDIT");
        assertThat(event.getChannelName()).isEqualTo("mock");
        assertThat(event.getSupplier()).isEqualTo(SmsSupplier.MOCK);
        assertThat(event.getResult()).isNotNull();
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.isFailed()).isFalse();
        assertThat(event.isBlocked()).isFalse();
        assertThat(event.getCostMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(event.getAuditTimestamp()).isNotNull();
    }
}
