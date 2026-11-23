package io.github.atengk.boot.admin.security;

import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminLoginDefenseService 登录防爆破与可信反代 IP 提取单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AdminLoginDefenseService 登录防爆破单元与 Fail-Open 测试")
class AdminLoginDefenseServiceTest {

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.current;
        }
    }

    @Test
    @DisplayName("确定性 Clock 接缝：非白名单 IP 连续失败达阈值触发账号与 IP 双轨锁定，窗口过期或人工解锁后恢复")
    void shouldLockAccountAndIpOnThresholdAndRecoverOnExpiryOrUnlock() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        AtengAdminServerProperties.LoginDefenseProperties defenseProps = properties.getLoginDefense();
        defenseProps.setEnabled(true);
        defenseProps.setMaxFailures(3);
        defenseProps.setFailureWindow(Duration.ofMinutes(10));
        defenseProps.setLockDuration(Duration.ofMinutes(15));
        defenseProps.setIpWhitelist(List.of("127.0.0.1", "::1"));

        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T04:00:00Z"));
        AdminLoginDefenseService service = new AdminLoginDefenseService(properties, null, clock);

        MockHttpServletRequest attackerReq = new MockHttpServletRequest("POST", "/admin/login");
        attackerReq.setRemoteAddr("203.0.113.50");

        assertThat(service.checkAllowed("ops-admin", attackerReq).isAllowed()).isTrue();

        // 第 1、2 次失败未锁
        assertThat(service.recordFailure("ops-admin", attackerReq).isLocked()).isFalse();
        assertThat(service.recordFailure("ops-admin", attackerReq).getRemainingAttempts()).isEqualTo(1);

        // 第 3 次失败触发锁定
        AdminLoginDefenseService.LoginDefenseDecision lockDecision = service.recordFailure("ops-admin", attackerReq);
        assertThat(lockDecision.isLocked()).isTrue();
        assertThat(lockDecision.isAllowed()).isFalse();
        assertThat(lockDecision.getRemainingLockSeconds()).isEqualTo(15 * 60L);

        // 同 IP 换账号被 IP 轨拒绝
        assertThat(service.checkAllowed("other-user", attackerReq).isLocked()).isTrue();
        // 同账号换非白名单 IP 被账号轨拒绝
        MockHttpServletRequest anotherIpReq = new MockHttpServletRequest("POST", "/admin/login");
        anotherIpReq.setRemoteAddr("198.51.100.8");
        assertThat(service.checkAllowed("ops-admin", anotherIpReq).isLocked()).isTrue();

        // 推进 14 分钟仍处于锁定期
        clock.advance(Duration.ofMinutes(14));
        assertThat(service.checkAllowed("ops-admin", attackerReq).isLocked()).isTrue();

        // 推进超过 15 分钟锁定期后自动解除
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.checkAllowed("ops-admin", attackerReq).isAllowed()).isTrue();

        // 再次触发锁定并通过人工 unlock 立即恢复
        service.recordFailure("ops-admin", attackerReq);
        service.recordFailure("ops-admin", attackerReq);
        service.recordFailure("ops-admin", attackerReq);
        assertThat(service.isLocked("ops-admin", "203.0.113.50")).isTrue();

        service.unlock("ops-admin", "203.0.113.50");
        assertThat(service.isLocked("ops-admin", "203.0.113.50")).isFalse();
        assertThat(service.checkAllowed("ops-admin", attackerReq).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("可信反代网段精确提取 X-Forwarded-For 首跳真实 IP，伪造代理头绕过无效")
    void shouldExtractXffOnlyFromTrustedProxyAndRejectHeaderSpoofing() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.getLoginDefense().setMaxFailures(2);
        properties.getLoginDefense().setTrustedProxies(List.of("10.10.0.0/16"));
        properties.getLoginDefense().setIpWhitelist(List.of("127.0.0.1"));

        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T04:00:00Z"));
        AdminLoginDefenseService service = new AdminLoginDefenseService(properties, null, clock);

        // 1. 直连来自不可信公网 IP 伪造 X-Forwarded-For: 127.0.0.1 试图绕过防爆破
        MockHttpServletRequest spoofReq = new MockHttpServletRequest("POST", "/admin/login");
        spoofReq.setRemoteAddr("198.51.100.77");
        spoofReq.addHeader("X-Forwarded-For", "127.0.0.1, 10.10.1.5");
        assertThat(service.resolveClientIp(spoofReq)).isEqualTo("198.51.100.77");

        service.recordFailure("admin", spoofReq);
        service.recordFailure("admin", spoofReq);
        assertThat(service.checkAllowed("admin", spoofReq).isLocked()).isTrue();

        // 2. 直连来自可信反代 10.10.1.5，正确提取 X-Forwarded-For 首跳真实客户端 IP
        MockHttpServletRequest proxiedReq = new MockHttpServletRequest("POST", "/admin/login");
        proxiedReq.setRemoteAddr("10.10.1.5");
        proxiedReq.addHeader("X-Forwarded-For", "203.0.113.99:54321, 10.10.1.5");
        assertThat(service.resolveClientIp(proxiedReq)).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("白名单 IP 免于爆破锁定；Redis 协调异常时自动 Fail-Open 降级本机计数")
    void shouldExemptWhitelistedIpAndFailOpenOnRedisError() {
        AtengAdminServerProperties properties = new AtengAdminServerProperties();
        properties.getLoginDefense().setMaxFailures(2);
        properties.getLoginDefense().setIpWhitelist(List.of("127.0.0.1", "::1"));

        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T04:00:00Z"));
        AdminLoginDefenseService service = new AdminLoginDefenseService(properties, brokenRedis, clock);

        MockHttpServletRequest localReq = new MockHttpServletRequest("POST", "/admin/login");
        localReq.setRemoteAddr("127.0.0.1");
        for (int i = 0; i < 5; i++) {
            assertThat(service.recordFailure("admin", localReq).isLocked()).isFalse();
        }
        assertThat(service.checkAllowed("admin", localReq).isAllowed()).isTrue();

        MockHttpServletRequest remoteReq = new MockHttpServletRequest("POST", "/admin/login");
        remoteReq.setRemoteAddr("203.0.113.200");
        service.recordFailure("remote-user", remoteReq);
        AdminLoginDefenseService.LoginDefenseDecision locked = service.recordFailure("remote-user", remoteReq);
        assertThat(locked.isLocked()).isTrue();
        verify(brokenRedis, atLeastOnce()).opsForValue();
    }
}
