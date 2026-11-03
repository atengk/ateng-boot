package io.github.atengk.boot.sms;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sms.config.AtengSmsAutoConfiguration;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 短信核心门面与 Mock 沙箱离线自洽测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = AtengSmsAutoConfiguration.class)
@DisplayName("短信核心门面与 Mock 沙箱离线自洽测试")
class SmsTemplateMockTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Autowired
    private MockSmsProvider mockSmsProvider;

    @BeforeEach
    void setUp() {
        mockSmsProvider.clear();
    }

    @Test
    @DisplayName("测试：默认 Mock 沙箱发信成功")
    void testSendMockSuccess() {
        SmsRequest request = SmsRequest.of("13812345678", "SMS_TEST_001", SmsParam.of("code", "888888"));

        SmsResult result = smsTemplate.send(request);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("mock", result.getChannel());
        assertEquals(SmsSupplier.MOCK, result.getSupplier());
        assertEquals(SmsDeliveryStatus.MOCKED, result.getDeliveryStatus());
        assertNotNull(result.getBizId());
        assertNotNull(result.getRequestId());
        assertEquals(1, result.getFee());

        // 断言 MockSmsProvider 纯内存成功记录
        assertEquals(1, mockSmsProvider.getSentCount());
        SmsRequest captured = mockSmsProvider.getLastRequest();
        assertNotNull(captured);
        assertEquals("13812345678", captured.getPhone());
        assertEquals("SMS_TEST_001", captured.getTemplateId());
        assertEquals("888888", captured.getParams().getMapParams().get("code"));
    }

    @Test
    @DisplayName("测试：手机号在内存保持真明文，toString() 自动脱敏打码")
    void testPhoneDesensitization() {
        SmsRequest request = SmsRequest.of("13812345678", "SMS_TEST_002");

        // 内存保持 100% 原始真实明文
        assertEquals("13812345678", request.getPhone());

        // toString 必须经过脱敏打码
        String str = request.toString();
        assertFalse(str.contains("13812345678"), "toString 绝不能输出原始手机号明文");
        assertTrue(str.contains("138****5678"), "toString 必须包含脱敏掩码");
    }

    @Test
    @DisplayName("测试：SmsParam 双模构建与自适应转换")
    void testSmsParamConversions() {
        // 1. 键值对 Map 模式
        SmsParam mapParam = SmsParam.of("code", "123456").add("minute", "5");
        assertEquals("123456", mapParam.getMapParams().get("code"));
        assertEquals("5", mapParam.getMapParams().get("minute"));
        assertTrue(mapParam.toJsonString().contains("\"code\":\"123456\""));

        // 声明式顺序映射推导
        List<String> ordered = mapParam.toOrderedArray(List.of("code", "minute"));
        assertEquals(List.of("123456", "5"), ordered);

        // 2. 原生数组模式
        SmsParam arrayParam = SmsParam.ofArray("999999", "10");
        assertTrue(arrayParam.isArrayMode());
        assertEquals(List.of("999999", "10"), arrayParam.getArrayParams());
        assertEquals(List.of("999999", "10"), arrayParam.toOrderedArray(null));
    }

    @Test
    @DisplayName("测试：前置卫语句快速失败拦截非法入参")
    void testFailFastValidations() {
        // 1. 空手机号校验
        assertThrows(BusinessException.class, () ->
                SmsRequest.of("", "SMS_TEST")
        );

        // 2. 空模板ID校验
        assertThrows(BusinessException.class, () ->
                SmsRequest.of("13812345678", "  ")
        );

        // 3. 发送 null 请求校验
        assertThrows(BusinessException.class, () ->
                smsTemplate.send(null)
        );

        // 4. 发送到不存在的渠道校验
        SmsRequest requestToUnknown = SmsRequest.builder()
                .phone("13812345678")
                .templateId("SMS_TEST")
                .channel("non-existent-channel")
                .build();
        assertThrows(SmsSendException.class, () ->
                smsTemplate.send(requestToUnknown)
        );
    }
}
