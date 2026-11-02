package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsRateLimitProperties;
import io.github.atengk.boot.sms.properties.SmsVerifyCodeProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.repository.InMemorySmsVerifyCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证码全生命周期与频控防刷单元测试
 * 100% 离线自洽，验证生成、发送、原子核销、防重放、连续输错冻结与滑动窗口限流
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("验证码全生命周期门面与防刷流控离线自洽测试")
class SmsVerifyCodeTemplateTest {

    private MockSmsProvider mockSmsProvider;
    private SmsTemplate smsTemplate;
    private SmsProperties properties;
    private InMemorySmsRateLimiter rateLimiter;
    private InMemorySmsVerifyCodeRepository repository;
    private SmsVerifyCodeTemplate verifyCodeTemplate;

    @BeforeEach
    void setUp() {
        mockSmsProvider = new MockSmsProvider();
        properties = new SmsProperties();

        // 配置默认 mock 渠道
        SmsChannelProperties channelProps = new SmsChannelProperties();
        channelProps.setSignName("测试签名");
        properties.getChannels().put("mock", channelProps);

        // 验证码配置：5分钟有效期，60秒重发冷却，最多错5次
        properties.getVerifyCode().setLength(6);
        properties.getVerifyCode().setTtl(Duration.ofMinutes(5));
        properties.getVerifyCode().setResendInterval(Duration.ofSeconds(60));
        properties.getVerifyCode().setMaxRetryAttempts(5);

        smsTemplate = new SmsTemplate(properties, List.of(mockSmsProvider));
        rateLimiter = new InMemorySmsRateLimiter(properties.getRateLimit());
        repository = new InMemorySmsVerifyCodeRepository();

        verifyCodeTemplate = new SmsVerifyCodeTemplate(smsTemplate, properties, rateLimiter, repository);
    }

    @Test
    @DisplayName("测试生成并发送验证码成功 (Happy Path)")
    void testGenerateAndSend_HappyPath() {
        String phone = "13800138000";
        String bizType = "login";

        String code = verifyCodeTemplate.generateAndSend(phone, bizType);

        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d{6}"), "验证码应为6位纯数字");

        // 断言 Mock 底座收到发信请求
        assertEquals(1, mockSmsProvider.getSentCount());
        assertNotNull(mockSmsProvider.getLastRequest());
        assertEquals(phone, mockSmsProvider.getLastRequest().getPhone());
        assertEquals(code, mockSmsProvider.getLastRequest().getParams().getMapParams().get("code"));
    }

    @Test
    @DisplayName("测试验证码成功校验后立即原子物理销毁 (杜绝重放攻击)")
    void testVerify_AtomicDestruction_PreventReplayAttack() {
        String phone = "13800138001";
        String bizType = "register";

        String code = verifyCodeTemplate.generateAndSend(phone, bizType);

        // 1. 首次验证通过并立即物理销毁
        boolean firstVerify = verifyCodeTemplate.verify(phone, code, bizType);
        assertTrue(firstVerify);

        // 2. 第二次使用相同验证码核验，必须抛出不存在或已过期异常（防重放）
        SmsVerifyCodeException ex = assertThrows(SmsVerifyCodeException.class,
                () -> verifyCodeTemplate.verify(phone, code, bizType));
        assertTrue(ex.getMessage().contains("不存在或已过期"));
    }

    @Test
    @DisplayName("测试连续输错扣减剩余次数与达到5次上限后强制冻结作废 (防暴力破解)")
    void testVerify_BruteForceProtection_FreezeOnMaxErrors() {
        String phone = "13800138002";
        String bizType = "reset_pwd";

        String correctCode = verifyCodeTemplate.generateAndSend(phone, bizType);
        String wrongCode = "000000";

        // 第 1 次输错：剩余 4 次
        SmsVerifyCodeException e1 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, wrongCode, bizType));
        assertEquals(4, e1.getRemainingAttempts());
        assertFalse(e1.isFrozen());

        // 第 2 次输错：剩余 3 次
        SmsVerifyCodeException e2 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, wrongCode, bizType));
        assertEquals(3, e2.getRemainingAttempts());

        // 第 3 次输错：剩余 2 次
        SmsVerifyCodeException e3 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, wrongCode, bizType));
        assertEquals(2, e3.getRemainingAttempts());

        // 第 4 次输错：剩余 1 次
        SmsVerifyCodeException e4 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, wrongCode, bizType));
        assertEquals(1, e4.getRemainingAttempts());

        // 第 5 次输错：达到上限，直接冻结
        SmsVerifyCodeException e5 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, wrongCode, bizType));
        assertEquals(0, e5.getRemainingAttempts());
        assertTrue(e5.isFrozen());
        assertTrue(e5.getMessage().contains("已被锁定"));

        // 第 6 次即便输入正确的验证码，也依然保持冻结拒绝
        SmsVerifyCodeException e6 = assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, correctCode, bizType));
        assertTrue(e6.isFrozen());
        assertTrue(e6.getMessage().contains("已被锁定"));
    }

    @Test
    @DisplayName("测试 60 秒重发冷却间隔拦截")
    void testResendInterval_CoolDownProtection() {
        String phone = "13800138003";
        String bizType = "bind";

        // 第一次发信成功
        verifyCodeTemplate.generateAndSend(phone, bizType);

        // 在 60 秒冷却期内立即再次申请，必须被快速拦截
        SmsRateLimitException ex = assertThrows(SmsRateLimitException.class,
                () -> verifyCodeTemplate.generateAndSend(phone, bizType));

        assertTrue(ex.getRetryAfterSeconds() > 0);
        assertTrue(ex.getMessage().contains("等待"));
    }

    @Test
    @DisplayName("测试纯内存多阶滑动窗口频控限流器 (1m/1h/1d)")
    void testInMemoryRateLimiter_SlidingWindow() {
        SmsRateLimitProperties limitProps = new SmsRateLimitProperties();
        limitProps.setPerMinute(2); // 1分钟最多2条
        limitProps.setPerHour(5);
        limitProps.setPerDay(10);

        InMemorySmsRateLimiter limiter = new InMemorySmsRateLimiter(limitProps);
        String phone = "13800138004";

        // 第 1 次许可
        assertTrue(limiter.tryAcquire(phone));
        // 第 2 次许可
        assertTrue(limiter.tryAcquire(phone));
        // 第 3 次超限拦截
        assertFalse(limiter.tryAcquire(phone));

        SmsRateLimitException ex = assertThrows(SmsRateLimitException.class, () -> limiter.acquire(phone));
        assertTrue(ex.getRetryAfterSeconds() > 0);

        // 重置后可重新获取
        limiter.reset(phone);
        assertTrue(limiter.tryAcquire(phone));
    }

    @Test
    @DisplayName("测试只读核对 check 与主动作废 invalidate")
    void testCheckAndInvalidate() {
        String phone = "13800138005";
        String bizType = "login";

        String code = verifyCodeTemplate.generateAndSend(phone, bizType);

        // 1. check 只读核对，返回 true
        assertTrue(verifyCodeTemplate.check(phone, code, bizType));

        // 2. 再次 check 依然有效（未被删除）
        assertTrue(verifyCodeTemplate.check(phone, code, bizType));

        // 3. 错误验证码 check 返回 false
        assertFalse(verifyCodeTemplate.check(phone, "999999", bizType));

        // 4. 主动作废
        verifyCodeTemplate.invalidate(phone, bizType);

        // 5. 作废后再 check 返回 false，verify 抛出不存在异常
        assertFalse(verifyCodeTemplate.check(phone, code, bizType));
        assertThrows(SmsVerifyCodeException.class, () -> verifyCodeTemplate.verify(phone, code, bizType));
    }

    @Test
    @DisplayName("测试字母数字混合模式生成验证码")
    void testAlphaNumericCharType() {
        properties.getVerifyCode().setCharType(SmsVerifyCodeProperties.CharType.ALPHA_NUMERIC);
        properties.getVerifyCode().setLength(8);

        String code = verifyCodeTemplate.generateAndSend("13800138006", "secure_login");
        assertNotNull(code);
        assertEquals(8, code.length());
    }
}
