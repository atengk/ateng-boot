package io.github.atengk.boot.sample.sms;

import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地服务真实公有云联调端到端集成测试（仅在激活 -Dspring.profiles.active=local 且配置了真实凭据时执行）
 * 默认/CI 构建时自动静默忽略，保证 GitHub Actions CI 100% 离线自洽
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Profile("local")
@SpringBootTest
@DisplayName("本地真实公有云发信联调集成测试 (需激活 local Profile)")
class SampleSmsLocalRealIntegrationTest {

    @Autowired
    private SmsTemplate smsTemplate;

    @Test
    @DisplayName("验证直连本地公有云真实发信链路")
    void testRealSmsSend() {
        SmsRequest request = SmsRequest.of("13800138000", "SMS_REAL_TEST", SmsParam.of("code", "888888"));
        SmsResult result = smsTemplate.send(request);

        assertNotNull(result);
        assertTrue(result.isSuccess(), "真实发信应投递成功");
    }
}
