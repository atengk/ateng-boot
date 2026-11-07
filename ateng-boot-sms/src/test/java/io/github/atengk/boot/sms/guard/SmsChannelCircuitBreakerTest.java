package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 短信渠道健康度短时熔断器单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
class SmsChannelCircuitBreakerTest {

    @Test
    @DisplayName("测试：正常闭路状态允许请求，发信成功清空失败计数")
    void testClosed_AllowRequestAndSuccess() {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerFailureThreshold(3);
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        assertTrue(breaker.allowRequest("aliyun"));
        assertFalse(breaker.isOpen("aliyun"));
        assertEquals(SmsChannelCircuitBreaker.State.CLOSED, breaker.getState("aliyun"));

        breaker.recordFailure("aliyun");
        assertEquals(1, breaker.getConsecutiveFailures("aliyun"));

        breaker.recordSuccess("aliyun");
        assertEquals(0, breaker.getConsecutiveFailures("aliyun"));
        assertEquals(SmsChannelCircuitBreaker.State.CLOSED, breaker.getState("aliyun"));
    }

    @Test
    @DisplayName("测试：连续失败达到阈值触发开路熔断 (OPEN)，短路拦截请求")
    void testTripToOpenOnThreshold() {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerFailureThreshold(3);
        props.setCircuitBreakerOpenDuration(Duration.ofSeconds(10));
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        breaker.recordFailure("tencent");
        assertFalse(breaker.isOpen("tencent"));
        breaker.recordFailure("tencent");
        assertFalse(breaker.isOpen("tencent"));

        // 第 3 次失败，触发熔断
        breaker.recordFailure("tencent");
        assertTrue(breaker.isOpen("tencent"));
        assertFalse(breaker.allowRequest("tencent"));
        assertEquals(SmsChannelCircuitBreaker.State.OPEN, breaker.getState("tencent"));
    }

    @Test
    @DisplayName("测试：开关关闭时始终允许请求且不触发熔断")
    void testDisabledCircuitBreaker() {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerEnabled(false);
        props.setCircuitBreakerFailureThreshold(2);
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        breaker.recordFailure("mock");
        breaker.recordFailure("mock");
        breaker.recordFailure("mock");

        assertTrue(breaker.allowRequest("mock"));
        assertFalse(breaker.isOpen("mock"));
        assertEquals(SmsChannelCircuitBreaker.State.CLOSED, breaker.getState("mock"));
    }

    @Test
    @DisplayName("测试：熔断冷却到期后进入半开状态 (HALF_OPEN)，试探成功恢复闭路 (CLOSED)")
    void testHalfOpenProbeSuccess() throws InterruptedException {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerFailureThreshold(2);
        props.setCircuitBreakerOpenDuration(Duration.ofMillis(100)); // 100ms 极短冷却用于测试
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        breaker.recordFailure("huawei");
        breaker.recordFailure("huawei");
        assertTrue(breaker.isOpen("huawei"));

        // 等待冷却到期
        Thread.sleep(120);

        // 冷却已过，状态进入 HALF_OPEN，并允许试探发信
        assertEquals(SmsChannelCircuitBreaker.State.HALF_OPEN, breaker.getState("huawei"));
        assertTrue(breaker.allowRequest("huawei"));
        assertFalse(breaker.isOpen("huawei"));

        // 试探成功，恢复 CLOSED
        breaker.recordSuccess("huawei");
        assertEquals(SmsChannelCircuitBreaker.State.CLOSED, breaker.getState("huawei"));
        assertFalse(breaker.isOpen("huawei"));
        assertEquals(0, breaker.getConsecutiveFailures("huawei"));
    }

    @Test
    @DisplayName("测试：半开状态试探失败，立即重新触发开路熔断 (OPEN)")
    void testHalfOpenProbeFailure() throws InterruptedException {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerFailureThreshold(2);
        props.setCircuitBreakerOpenDuration(Duration.ofMillis(100));
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        breaker.recordFailure("ctyun");
        breaker.recordFailure("ctyun");
        assertTrue(breaker.isOpen("ctyun"));

        Thread.sleep(120);
        assertEquals(SmsChannelCircuitBreaker.State.HALF_OPEN, breaker.getState("ctyun"));

        // 试探失败，立即切回 OPEN
        breaker.recordFailure("ctyun");
        assertTrue(breaker.isOpen("ctyun"));
        assertFalse(breaker.allowRequest("ctyun"));
        assertEquals(SmsChannelCircuitBreaker.State.OPEN, breaker.getState("ctyun"));
    }

    @Test
    @DisplayName("测试：手动重置单个渠道与重置全部渠道")
    void testManualReset() {
        SmsGuardProperties props = new SmsGuardProperties();
        props.setCircuitBreakerFailureThreshold(1);
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker(props);

        breaker.recordFailure("ch1");
        breaker.recordFailure("ch2");
        assertTrue(breaker.isOpen("ch1"));
        assertTrue(breaker.isOpen("ch2"));

        breaker.reset("ch1");
        assertFalse(breaker.isOpen("ch1"));
        assertTrue(breaker.isOpen("ch2"));

        breaker.resetAll();
        assertFalse(breaker.isOpen("ch1"));
        assertFalse(breaker.isOpen("ch2"));
    }

    @Test
    @DisplayName("测试：前置卫语句防卫空入参")
    void testPreconditions() {
        SmsChannelCircuitBreaker breaker = new SmsChannelCircuitBreaker();
        assertThrows(BusinessException.class, () -> breaker.allowRequest(null));
        assertThrows(BusinessException.class, () -> breaker.allowRequest(" "));
        assertThrows(BusinessException.class, () -> breaker.recordSuccess(""));
        assertThrows(BusinessException.class, () -> breaker.recordFailure(null));
        assertThrows(BusinessException.class, () -> breaker.isOpen(null));
        assertThrows(BusinessException.class, () -> breaker.reset("   "));
    }
}
