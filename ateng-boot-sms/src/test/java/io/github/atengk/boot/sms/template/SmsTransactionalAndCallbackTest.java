package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.sms.callback.SmsCallbackController;
import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地事务感知发信、失败补偿扩展与异步回执/上行短信事件网关全维自洽单测
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(
        classes = {AtengSmsAutoConfiguration.class, SmsTransactionalAndCallbackTest.TestEventListenerConfig.class},
        properties = "ateng.boot.sms.callback.enabled=true"
)
@DisplayName("本地事务感知发信与异步回执/上行短信事件网关测试")
class SmsTransactionalAndCallbackTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @Autowired
    private SmsChannelRegistry channelRegistry;

    @Autowired
    private SmsCallbackController smsCallbackController;

    @Autowired
    private TestEventListenerConfig.EventCaptureListener eventCaptureListener;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
        eventCaptureListener.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);

        // 初始化三大厂商的测试渠道配置
        channelRegistry.register(SmsChannel.builder()
                .name("aliyun")
                .supplier(SmsSupplier.ALIYUN)
                .signName("阿里签名")
                .build());
        channelRegistry.register(SmsChannel.builder()
                .name("tencent")
                .supplier(SmsSupplier.TENCENT)
                .signName("腾讯签名")
                .build());
        channelRegistry.register(SmsChannel.builder()
                .name("huawei")
                .supplier(SmsSupplier.HUAWEI)
                .signName("华为签名")
                .build());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        eventCaptureListener.clear();
    }

    @Test
    @DisplayName("测试：sendAfterCommit 在活动事务 COMMIT 后触发发信")
    void testSendAfterCommit_SuccessOnCommit() {
        SmsRequest request = SmsRequest.of("13800010001", "SMS_TX_01", SmsParam.of("code", "6666"));

        // 模拟 Spring 真实事务环境开启
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // 调用事务感知发信
            smsTemplate.sendAfterCommit(request);

            // 事务未提交前，底层 Mock 沙箱不得接收到任何发信（幽灵短信防卫生效）
            assertEquals(0, mockSmsProvider.getSentCount(), "事务提交前不得真实发信");

            // 模拟事务成功提交 (COMMIT)
            TransactionSynchronizationUtils.triggerAfterCommit();

            // 事务提交后，触发真实投递
            assertEquals(1, mockSmsProvider.getSentCount(), "事务提交后应触发真实发信");
            assertEquals("13800010001", mockSmsProvider.getLastRequest().getPhone());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("测试：sendAfterCommit 在事务 ROLLBACK 时安全取消投递 (防幽灵短信)")
    void testSendAfterCommit_CancelledOnRollback() {
        SmsRequest request = SmsRequest.of("13800010002", "SMS_TX_02", SmsParam.of("code", "7777"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            smsTemplate.sendAfterCommit(request);
            assertEquals(0, mockSmsProvider.getSentCount());

            // 模拟事务发生异常回滚，不调用 triggerAfterCommit，直接清理同步器
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        // 回滚后最终发信条数依然为 0
        assertEquals(0, mockSmsProvider.getSentCount(), "事务回滚后不得发送短信");
    }

    @Test
    @DisplayName("测试：sendAfterCommit 无事务环境下直接同步发信保底")
    void testSendAfterCommit_NoTransactionDirectSend() {
        SmsRequest request = SmsRequest.of("13800010003", "SMS_TX_03", SmsParam.of("code", "8888"));

        // 无活动事务环境直接调用
        smsTemplate.sendAfterCommit(request);

        assertEquals(1, mockSmsProvider.getSentCount());
        assertEquals("13800010003", mockSmsProvider.getLastRequest().getPhone());
    }

    @Test
    @DisplayName("测试：SmsSendFailureHandler 补偿 SPI 在主备渠道均投递失败时触发")
    void testSmsSendFailureHandler_TriggeredOnFinalFailure() {
        AtomicBoolean handlerTriggered = new AtomicBoolean(false);
        List<String> capturedPhones = new ArrayList<>();

        SmsSendFailureHandler customHandler = (req, targetChannel, cause) -> {
            handlerTriggered.set(true);
            capturedPhones.add(req.getPhone());
            assertNotNull(targetChannel);
            assertNotNull(cause);
        };

        // 构造一个必定失败的 Provider
        SmsProvider failProvider = new SmsProvider() {
            @Override
            public SmsSupplier getSupplier() {
                return SmsSupplier.MOCK;
            }

            @Override
            public SmsResult send(SmsRequest req, SmsChannel ch) {
                throw new SmsSendException(ch.getName(), "FAIL_ALL", "全链路物理故障", null, null);
            }
        };

        SmsChannelRegistry testRegistry = new SmsChannelRegistry();
        testRegistry.register(SmsChannel.builder().name("failing-channel").supplier(SmsSupplier.MOCK).build());

        SmsTemplate failingTemplate = new SmsTemplate(
                null,
                List.of(failProvider),
                testRegistry,
                null,
                List.of(customHandler),
                null,
                null,
                null
        );

        SmsRequest request = SmsRequest.of("13800019999", "SMS_FAIL", SmsParam.create());

        assertThrows(SmsSendException.class, () -> failingTemplate.send(request));
        assertTrue(handlerTriggered.get(), "应触发 SmsSendFailureHandler 补偿逻辑");
        assertEquals(List.of("13800019999"), capturedPhones);
    }

    @Test
    @DisplayName("测试：模拟阿里云送达回执回调转译并广播 SmsDeliveryReportEvent")
    void testCallback_AliyunDeliveryReport() {
        String aliyunReportJson = """
                [
                  {
                    "phone_number": "13800020001",
                    "send_time": "2026-11-02 20:00:00",
                    "report_time": "2026-11-02 20:00:05",
                    "success": true,
                    "err_code": "DELIVRD",
                    "err_msg": "用户接收成功",
                    "sms_size": "1",
                    "biz_id": "ALI_BIZ_123456"
                  }
                ]
                """;

        Map<String, Object> resp = smsCallbackController.handleCallback("aliyun", aliyunReportJson, null, new HttpHeaders());
        assertNotNull(resp);
        assertEquals(0, resp.get("code"));
        assertEquals("成功", resp.get("msg"));

        assertEquals(1, eventCaptureListener.deliveryReportEvents.size());
        SmsDeliveryReportEvent event = eventCaptureListener.deliveryReportEvents.get(0);
        assertEquals("aliyun", event.getChannel());
        assertEquals(SmsSupplier.ALIYUN, event.getSupplier());
        assertEquals("13800020001", event.getPhone());
        assertTrue(event.isSuccess());
        assertEquals("DELIVRD", event.getResultCode());
        assertEquals("ALI_BIZ_123456", event.getBizId());
    }

    @Test
    @DisplayName("测试：模拟腾讯云送达回执回调转译并广播 SmsDeliveryReportEvent")
    void testCallback_TencentDeliveryReport() {
        String tencentReportJson = """
                [
                  {
                    "user_receive_time": "2026-11-02 20:05:00",
                    "nationcode": "86",
                    "mobile": "13800020002",
                    "report_status": "SUCCESS",
                    "errmsg": "DELIVRD",
                    "description": "SUCCESS",
                    "sid": "TC_SID_888888"
                  }
                ]
                """;

        Map<String, Object> resp = smsCallbackController.handleCallback("tencent", tencentReportJson, null, new HttpHeaders());
        assertNotNull(resp);
        assertEquals(0, resp.get("result"));
        assertEquals("OK", resp.get("errmsg"));

        assertEquals(1, eventCaptureListener.deliveryReportEvents.size());
        SmsDeliveryReportEvent event = eventCaptureListener.deliveryReportEvents.get(0);
        assertEquals("tencent", event.getChannel());
        assertEquals(SmsSupplier.TENCENT, event.getSupplier());
        assertEquals("13800020002", event.getPhone());
        assertTrue(event.isSuccess());
        assertEquals("DELIVRD", event.getResultCode());
        assertEquals("TC_SID_888888", event.getBizId());
    }

    @Test
    @DisplayName("测试：模拟华为云送达回执回调转译并广播 SmsDeliveryReportEvent")
    void testCallback_HuaweiDeliveryReport() {
        String huaweiReportJson = """
                {
                  "smsMsgId": "HW_MSG_999999",
                  "total": 1,
                  "status": "DELIVRD",
                  "statusDesc": "Success",
                  "originTo": "13800020003"
                }
                """;

        Map<String, Object> resp = smsCallbackController.handleCallback("huawei", huaweiReportJson, null, new HttpHeaders());
        assertNotNull(resp);
        assertEquals("0", resp.get("result"));

        assertEquals(1, eventCaptureListener.deliveryReportEvents.size());
        SmsDeliveryReportEvent event = eventCaptureListener.deliveryReportEvents.get(0);
        assertEquals("huawei", event.getChannel());
        assertEquals(SmsSupplier.HUAWEI, event.getSupplier());
        assertEquals("13800020003", event.getPhone());
        assertTrue(event.isSuccess());
        assertEquals("DELIVRD", event.getResultCode());
        assertEquals("HW_MSG_999999", event.getBizId());
    }

    @Test
    @DisplayName("测试：模拟阿里云用户上行短信 (TD退订) 转译并广播 SmsMoMessageEvent")
    void testCallback_AliyunMoMessage() {
        String aliyunMoJson = """
                [
                  {
                    "phone_number": "13800030001",
                    "send_time": "2026-11-02 21:00:00",
                    "content": "TD",
                    "sign_name": "阿里测试签名"
                  }
                ]
                """;

        Map<String, Object> resp = smsCallbackController.handleCallback("aliyun", aliyunMoJson, null, new HttpHeaders());
        assertNotNull(resp);
        assertEquals(0, resp.get("code"));

        assertEquals(1, eventCaptureListener.moMessageEvents.size());
        SmsMoMessageEvent moEvent = eventCaptureListener.moMessageEvents.get(0);
        assertEquals("aliyun", moEvent.getChannel());
        assertEquals(SmsSupplier.ALIYUN, moEvent.getSupplier());
        assertEquals("13800030001", moEvent.getPhone());
        assertEquals("TD", moEvent.getContent());
        assertEquals("阿里测试签名", moEvent.getSignName());
    }

    @Test
    @DisplayName("测试：模拟腾讯云用户上行短信 (TD退订) 转译并广播 SmsMoMessageEvent")
    void testCallback_TencentMoMessage() {
        String tencentMoJson = """
                [
                  {
                    "user_receive_time": "2026-11-02 21:10:00",
                    "nationcode": "86",
                    "mobile": "13800030002",
                    "text": "TD退订",
                    "sign": "腾讯测试签名"
                  }
                ]
                """;

        Map<String, Object> resp = smsCallbackController.handleCallback("tencent", tencentMoJson, null, new HttpHeaders());
        assertNotNull(resp);
        assertEquals(0, resp.get("result"));

        assertEquals(1, eventCaptureListener.moMessageEvents.size());
        SmsMoMessageEvent moEvent = eventCaptureListener.moMessageEvents.get(0);
        assertEquals("tencent", moEvent.getChannel());
        assertEquals(SmsSupplier.TENCENT, moEvent.getSupplier());
        assertEquals("13800030002", moEvent.getPhone());
        assertEquals("TD退订", moEvent.getContent());
        assertEquals("腾讯测试签名", moEvent.getSignName());
    }

    /**
     * 测试事件监听捕获配置
     */
    @TestConfiguration
    static class TestEventListenerConfig {

        @Bean
        public EventCaptureListener eventCaptureListener() {
            return new EventCaptureListener();
        }

        static class EventCaptureListener {
            final List<SmsDeliveryReportEvent> deliveryReportEvents = new CopyOnWriteArrayList<>();
            final List<SmsMoMessageEvent> moMessageEvents = new CopyOnWriteArrayList<>();

            @EventListener
            public void onDeliveryReport(SmsDeliveryReportEvent event) {
                deliveryReportEvents.add(event);
            }

            @EventListener
            public void onMoMessage(SmsMoMessageEvent event) {
                moMessageEvents.add(event);
            }

            public void clear() {
                deliveryReportEvents.clear();
                moMessageEvents.clear();
            }
        }
    }
}
