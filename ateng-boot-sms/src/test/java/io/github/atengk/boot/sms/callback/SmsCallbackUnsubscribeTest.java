package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 云厂商 Webhook 回调、送达回执转译与用户上行 TD 退订自动加黑单测
 *
 * @author Ateng
 * @since 2026-11-07
 */
@ExtendWith(MockitoExtension.class)
class SmsCallbackUnsubscribeTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SmsProperties properties;
    private SmsChannelRegistry channelRegistry;
    private InMemorySmsBlacklistProvider blacklistProvider;

    @BeforeEach
    void setUp() {
        properties = new SmsProperties();
        properties.getCallback().setEnabled(true);
        properties.getGuard().setAutoBlacklistOnUnsubscribe(true);

        SmsChannelProperties yunpianChannel = new SmsChannelProperties();
        yunpianChannel.setSupplier(SmsSupplier.YUNPIAN);
        properties.getChannels().put("yunpian", yunpianChannel);

        SmsChannelProperties ctyunChannel = new SmsChannelProperties();
        ctyunChannel.setSupplier(SmsSupplier.CTYUN);
        properties.getChannels().put("ctyun", ctyunChannel);

        channelRegistry = new SmsChannelRegistry(properties);
        blacklistProvider = new InMemorySmsBlacklistProvider();
    }

    @Test
    @DisplayName("Yunpian: 送达回执报文转译为 SmsDeliveryReportEvent")
    void testYunpianDeliveryStatus() {
        SmsCallbackController controller = new SmsCallbackController(
                channelRegistry, eventPublisher, blacklistProvider, properties
        );

        String jsonStatus = "[{\"sid\":9527,\"mobile\":\"13800000001\",\"user_receive_time\":\"2026-11-07 10:00:00\",\"error_msg\":\"DELIVRD\",\"report_status\":\"SUCCESS\"}]";
        MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
        formParams.add("sms_status", jsonStatus);

        ResponseEntity<Map<String, Object>> response = controller.handleCallback("yunpian", null, formParams, new HttpHeaders());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        ArgumentCaptor<SmsDeliveryReportEvent> captor = ArgumentCaptor.forClass(SmsDeliveryReportEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        SmsDeliveryReportEvent event = captor.getValue();
        assertThat(event.getPhone()).isEqualTo("13800000001");
        assertThat(event.getChannel()).isEqualTo("yunpian");
        assertThat(event.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Yunpian: 用户上行回复 TD 触发自动加入出站黑名单")
    void testYunpianMoUnsubscribeAutoBlacklist() {
        SmsCallbackController controller = new SmsCallbackController(
                channelRegistry, eventPublisher, blacklistProvider, properties
        );

        String jsonReply = "[{\"mobile\":\"13800000002\",\"text\":\"TD\",\"reply_time\":\"2026-11-07 10:05:00\"}]";
        MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
        formParams.add("sms_reply", jsonReply);

        ResponseEntity<Map<String, Object>> response = controller.handleCallback("yunpian", null, formParams, new HttpHeaders());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        // 验证广播了 SmsMoMessageEvent
        ArgumentCaptor<SmsMoMessageEvent> captor = ArgumentCaptor.forClass(SmsMoMessageEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        SmsMoMessageEvent event = captor.getValue();
        assertThat(event.getPhone()).isEqualTo("13800000002");
        assertThat(event.getContent()).isEqualTo("TD");

        // 验证黑名单自动写入
        assertThat(blacklistProvider.isBlacklisted("13800000002")).isTrue();
        assertThat(blacklistProvider.getReason("13800000002")).contains("退订");
    }

    @Test
    @DisplayName("Ctyun: JSON 格式送达回执与上行退订闭环")
    void testCtyunDeliveryAndUnsubscribe() {
        SmsCallbackController controller = new SmsCallbackController(
                channelRegistry, eventPublisher, blacklistProvider, properties
        );

        // 1. 天翼云送达回执
        String ctyunReportJson = "{\"reports\":[{\"phone\":\"13800000003\",\"status\":\"DELIVRD\",\"msgId\":\"ctyun-999\"}]}";
        ResponseEntity<Map<String, Object>> reportResp = controller.handleCallback("ctyun", ctyunReportJson, null, new HttpHeaders());
        assertThat(reportResp.getStatusCode().is2xxSuccessful()).isTrue();

        verify(eventPublisher, atLeastOnce()).publishEvent(any(SmsDeliveryReportEvent.class));

        // 2. 天翼云上行回复退订 "退订"
        String ctyunReplyJson = "{\"replies\":[{\"phone\":\"13800000004\",\"content\":\"退订\",\"receiveTime\":\"2026-11-07 11:00:00\"}]}";
        ResponseEntity<Map<String, Object>> replyResp = controller.handleCallback("ctyun", ctyunReplyJson, null, new HttpHeaders());
        assertThat(replyResp.getStatusCode().is2xxSuccessful()).isTrue();

        verify(eventPublisher, atLeastOnce()).publishEvent(any(SmsMoMessageEvent.class));
        assertThat(blacklistProvider.isBlacklisted("13800000004")).isTrue();
    }

    @Test
    @DisplayName("上行普通消息或未开启退订自动加黑时不修改黑名单")
    void testNormalReplyDoesNotBlacklist() {
        SmsCallbackController controller = new SmsCallbackController(
                channelRegistry, eventPublisher, blacklistProvider, properties
        );

        // 普通回复 "1"
        String jsonReply = "[{\"mobile\":\"13800000005\",\"text\":\"1\",\"reply_time\":\"2026-11-07 10:05:00\"}]";
        MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
        formParams.add("sms_reply", jsonReply);
        controller.handleCallback("yunpian", null, formParams, new HttpHeaders());

        assertThat(blacklistProvider.isBlacklisted("13800000005")).isFalse();

        // 关闭 autoBlacklistOnUnsubscribe 时回复 "TD"
        properties.getGuard().setAutoBlacklistOnUnsubscribe(false);
        SmsCallbackController controllerNoAuto = new SmsCallbackController(
                channelRegistry, eventPublisher, blacklistProvider, properties
        );
        String jsonReplyTd = "[{\"mobile\":\"13800000006\",\"text\":\"TD\",\"reply_time\":\"2026-11-07 10:05:00\"}]";
        MultiValueMap<String, String> formParamsTd = new LinkedMultiValueMap<>();
        formParamsTd.add("sms_reply", jsonReplyTd);
        controllerNoAuto.handleCallback("yunpian", null, formParamsTd, new HttpHeaders());

        assertThat(blacklistProvider.isBlacklisted("13800000006")).isFalse();
    }
}
