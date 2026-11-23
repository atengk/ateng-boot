package io.github.atengk.boot.satoken.defense;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextDefaultImpl;
import cn.dev33.satoken.context.model.SaRequest;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LoginFailureLockService 单元测试
 * 覆盖密码连续输错计数、达到阈值自动封禁、客户端 IP 自动嗅探、白名单豁免、单 IP 爆破熔断拦截及过期自动恢复逻辑
 *
 * @author Ateng
 * @since 2026-10-20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginFailureLockService 单元测试")
class LoginFailureLockServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Mock
    private SaRequest saRequest;

    private SaTokenProperties properties;
    private LoginFailureLockService redisService;
    private LoginFailureLockService memoryService;

    private static final String TEST_ACCOUNT = "admin_attacker";
    private static final String TEST_IP = "198.51.100.1";

    @BeforeEach
    void setUp() {
        properties = new SaTokenProperties();
        properties.getLoginDefense().setEnabled(true);
        properties.getLoginDefense().setMaxRetryCount(5);
        properties.getLoginDefense().setLockTime(Duration.ofMinutes(15));
        properties.getLoginDefense().setWindowTime(Duration.ofMinutes(15));
        properties.getLoginDefense().setIpMaxRetryCount(30);
        properties.getLoginDefense().setIpLockTime(Duration.ofMinutes(30));
        properties.getLoginDefense().setIpWindowTime(Duration.ofMinutes(15));
        properties.getLoginDefense().setIpWhiteList(new ArrayList<>(Arrays.asList(
                "127.0.0.1",
                "0:0:0:0:0:0:0:1",
                "::1"
        )));

        // 1. Redis 模式实例
        redisService = new LoginFailureLockService(redisTemplateProvider, properties);

        // 2. 纯内存降级模式实例（传入 null Provider 触发降级分支）
        memoryService = new LoginFailureLockService(null, properties);
    }

    @AfterEach
    void tearDown() {
        // 上下文零污染：解封测试账号、清空本地内存并重置 Sa-Token 上下文
        try {
            LoginHelper.untieDisable(TEST_ACCOUNT);
            LoginHelper.untieDisable("mem_user");
            for (int i = 1; i <= 10; i++) {
                LoginHelper.untieDisable("victim_user_" + i);
            }
        } catch (Exception ignored) {
        }
        redisService.clearLocalMemory();
        memoryService.clearLocalMemory();
        SaManager.setSaTokenContext(SaTokenContextDefaultImpl.defaultContext);
    }

    @Test
    @DisplayName("Redis 模式：输错密码原子递增并在达到阈值时触发账号封禁")
    void testRedisRecordFailureAndLock() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String key = LoginFailureLockService.KEY_PREFIX + TEST_ACCOUNT;

        // 1. 第一次输错：计数 1，设置 TTL，剩余次数 4
        when(valueOperations.increment(key)).thenReturn(1L);
        int remain1 = redisService.recordFailure(TEST_ACCOUNT);
        assertThat(remain1).isEqualTo(4);
        verify(stringRedisTemplate).expire(eq(key), eq(Duration.ofMinutes(15)));

        // 2. 第二次输错：计数 2，剩余次数 3
        when(valueOperations.increment(key)).thenReturn(2L);
        int remain2 = redisService.recordFailure(TEST_ACCOUNT);
        assertThat(remain2).isEqualTo(3);

        // 3. 第五次输错：达到阈值 5，触发锁定，删除计数，剩余次数 0
        when(valueOperations.increment(key)).thenReturn(5L);
        int remain5 = redisService.recordFailure(TEST_ACCOUNT);
        assertThat(remain5).isEqualTo(0);
        verify(stringRedisTemplate).delete(key);
        assertThat(LoginHelper.isDisable(TEST_ACCOUNT)).isTrue();
    }

    @Test
    @DisplayName("Redis 模式：登录成功清空失败计数器")
    void testRedisClearFailure() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);

        redisService.clearFailure(TEST_ACCOUNT);
        verify(stringRedisTemplate).delete(LoginFailureLockService.KEY_PREFIX + TEST_ACCOUNT);
    }

    @Test
    @DisplayName("Redis 模式：查询剩余次数与失败次数")
    void testRedisGetRemainAndFailureCount() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String key = LoginFailureLockService.KEY_PREFIX + TEST_ACCOUNT;
        when(valueOperations.get(key)).thenReturn("3");

        int failCount = redisService.getFailureCount(TEST_ACCOUNT);
        assertThat(failCount).isEqualTo(3);

        int remain = redisService.getRemainRetryCount(TEST_ACCOUNT);
        assertThat(remain).isEqualTo(2); // 5 - 3 = 2
    }

    @Test
    @DisplayName("Redis 模式：客户端 IP 连续输错累计并在第 30 次触发熔断锁定")
    void testRedisRecordIpFailureAndLock() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String ipFailKey = LoginFailureLockService.KEY_IP_PREFIX + TEST_IP;
        String ipLockKey = LoginFailureLockService.KEY_IP_LOCK_PREFIX + TEST_IP;

        // 1. 第 1 次输错：计数 1，设置 TTL，剩余次数 29
        when(valueOperations.increment(ipFailKey)).thenReturn(1L);
        int ipRemain1 = redisService.recordIpFailure(TEST_IP);
        assertThat(ipRemain1).isEqualTo(29);
        verify(stringRedisTemplate).expire(eq(ipFailKey), eq(Duration.ofMinutes(15)));

        // 2. 第 30 次输错：达到阈值 30，设置锁定键，删除计数键，返回 0
        when(valueOperations.increment(ipFailKey)).thenReturn(30L);
        int ipRemain30 = redisService.recordIpFailure(TEST_IP);
        assertThat(ipRemain30).isEqualTo(0);
        verify(valueOperations).set(eq(ipLockKey), eq("LOCKED"), eq(Duration.ofMinutes(30)));
        verify(stringRedisTemplate).delete(ipFailKey);

        // 3. 校验 IP 锁定状态与 Fail-Fast 异常抛出
        when(stringRedisTemplate.hasKey(ipLockKey)).thenReturn(true);
        assertThat(redisService.isIpLocked(TEST_IP)).isTrue();
        assertThat(redisService.isLocked(TEST_ACCOUNT, TEST_IP)).isTrue();

        assertThatThrownBy(() -> redisService.checkIpLocked(TEST_IP))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户端 IP 连续登录失败次数超限，已被临时锁定")
                .matches(ex -> ((BusinessException) ex).getCode() == CommonResultCode.FORBIDDEN.getCode());

        // 4. 清理 IP 失败与锁定
        redisService.clearIpFailure(TEST_IP);
        verify(stringRedisTemplate, times(2)).delete(ipFailKey);
        verify(stringRedisTemplate).delete(ipLockKey);
    }

    @Test
    @DisplayName("内存降级模式：账号连续输错递增并在第 5 次自动触发封禁")
    void testMemoryModeContinuousFailureAndLock() {
        String account = "mem_user";

        // 验证初始状态
        assertThat(memoryService.getFailureCount(account)).isEqualTo(0);
        assertThat(memoryService.getRemainRetryCount(account)).isEqualTo(5);
        assertThat(memoryService.isLocked(account)).isFalse();

        // 第 1 次输错
        assertThat(memoryService.recordFailure(account)).isEqualTo(4);
        assertThat(memoryService.getFailureCount(account)).isEqualTo(1);
        assertThat(memoryService.getRemainRetryCount(account)).isEqualTo(4);

        // 第 2 次输错
        assertThat(memoryService.recordFailure(account)).isEqualTo(3);
        // 第 3 次输错
        assertThat(memoryService.recordFailure(account)).isEqualTo(2);
        // 第 4 次输错
        assertThat(memoryService.recordFailure(account)).isEqualTo(1);

        // 第 5 次输错：达到阈值 5，触发封禁并重置内存
        assertThat(memoryService.recordFailure(account)).isEqualTo(0);
        assertThat(memoryService.isLocked(account)).isTrue();
        assertThat(memoryService.getRemainRetryCount(account)).isEqualTo(0);
        assertThat(memoryService.getDisableTime(account)).isGreaterThan(0L);
        assertThat(memoryService.getLockTime(account)).isGreaterThan(0L);

        // 被封禁后再输错，依然返回 0
        assertThat(memoryService.recordFailure(account)).isEqualTo(0);
    }

    @Test
    @DisplayName("内存降级模式：客户端 IP 连续输错累计并在达到阈值时触发熔断")
    void testMemoryModeContinuousIpFailureAndLock() {
        String ip = "198.51.100.8";

        assertThat(memoryService.getIpFailureCount(ip)).isEqualTo(0);
        assertThat(memoryService.getIpRemainRetryCount(ip)).isEqualTo(30);
        assertThat(memoryService.isIpLocked(ip)).isFalse();

        // 模拟连续输错 29 次
        for (int i = 1; i <= 29; i++) {
            int remain = memoryService.recordIpFailure(ip);
            assertThat(remain).isEqualTo(30 - i);
            assertThat(memoryService.getIpFailureCount(ip)).isEqualTo(i);
        }
        assertThat(memoryService.isIpLocked(ip)).isFalse();

        // 第 30 次输错：触发 IP 熔断
        int remain30 = memoryService.recordIpFailure(ip);
        assertThat(remain30).isEqualTo(0);
        assertThat(memoryService.isIpLocked(ip)).isTrue();
        assertThat(memoryService.getIpLockTime(ip)).isGreaterThan(0L);
        assertThat(memoryService.getIpRemainRetryCount(ip)).isEqualTo(0);

        // 再次输错直接返回 0
        assertThat(memoryService.recordIpFailure(ip)).isEqualTo(0);

        // checkIpLocked 与 checkLocked 直接 Fail-Fast
        assertThatThrownBy(() -> memoryService.checkIpLocked(ip))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户端 IP 连续登录失败次数超限，已被临时锁定")
                .matches(ex -> ((BusinessException) ex).getCode() == CommonResultCode.FORBIDDEN.getCode());

        assertThatThrownBy(() -> memoryService.checkLocked("fresh_user", ip))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户端 IP 连续登录失败次数超限，已被临时锁定");

        // 手动解封
        memoryService.unlockIp(ip);
        assertThat(memoryService.isIpLocked(ip)).isFalse();
        assertThat(memoryService.getIpFailureCount(ip)).isEqualTo(0);
        memoryService.checkLocked("fresh_user", ip);
    }

    @Test
    @DisplayName("内存降级模式：客户端 IP 统计窗口与锁定时间自动过期恢复")
    void testMemoryModeIpExpirationAndUnlock() throws InterruptedException {
        properties.getLoginDefense().setIpWindowTime(Duration.ofMillis(15));
        properties.getLoginDefense().setIpLockTime(Duration.ofMillis(15));
        String ip = "198.51.100.9";

        // 1. 窗口过期重置计数
        memoryService.recordIpFailure(ip);
        assertThat(memoryService.getIpFailureCount(ip)).isEqualTo(1);
        Thread.sleep(25);
        assertThat(memoryService.getIpFailureCount(ip)).isEqualTo(0);
        assertThat(memoryService.getIpRemainRetryCount(ip)).isEqualTo(30);

        // 2. 达到阈值锁定后，等待锁定过期自动恢复
        properties.getLoginDefense().setIpMaxRetryCount(2);
        memoryService.recordIpFailure(ip);
        memoryService.recordIpFailure(ip);
        assertThat(memoryService.isIpLocked(ip)).isTrue();

        Thread.sleep(25);
        assertThat(memoryService.isIpLocked(ip)).isFalse();
        assertThat(memoryService.getIpLockTime(ip)).isEqualTo(0L);
    }

    @Test
    @DisplayName("透明自动嗅探：从 SaRequest 中提取 X-Forwarded-For 与 X-Real-IP 客户端真实 IP")
    void testClientIpAutoSniffing() {
        SaTokenContext mockContext = mock(SaTokenContext.class);
        when(mockContext.isValid()).thenReturn(true);
        when(mockContext.getRequest()).thenReturn(saRequest);
        SaManager.setSaTokenContext(mockContext);

        // 1. 优先从 X-Forwarded-For 提取首个合法 IP
        when(saRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195, 10.0.0.1");
        assertThat(memoryService.sniffClientIp()).isEqualTo("203.0.113.195");

        // 2. 当 X-Forwarded-For 包含 unknown 或恶意注入时跳过并识别有效 IP
        when(saRequest.getHeader("X-Forwarded-For")).thenReturn("unknown, <script>alert(1)</script>, 203.0.113.196");
        assertThat(memoryService.sniffClientIp()).isEqualTo("203.0.113.196");

        // 3. 当 X-Forwarded-For 为空时回退到 X-Real-IP
        when(saRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(saRequest.getHeader("X-Real-IP")).thenReturn("203.0.113.197");
        assertThat(memoryService.sniffClientIp()).isEqualTo("203.0.113.197");

        // 4. IPv6 本地环回地址规整为 127.0.0.1
        when(saRequest.getHeader("X-Real-IP")).thenReturn("0:0:0:0:0:0:0:1");
        assertThat(memoryService.sniffClientIp()).isEqualTo("127.0.0.1");

        when(saRequest.getHeader("X-Real-IP")).thenReturn("::1");
        assertThat(memoryService.sniffClientIp()).isEqualTo("127.0.0.1");

        // 5. 无显式传参时，recordFailure 自动关联嗅探到的客户端 IP
        when(saRequest.getHeader("X-Real-IP")).thenReturn("198.51.100.77");
        memoryService.recordFailure("sniff_user");
        assertThat(memoryService.getIpFailureCount("198.51.100.77")).isEqualTo(1);
    }

    @Test
    @DisplayName("客户端 IP 白名单豁免机制：本地回环与 CIDR 网段豁免防爆破")
    void testIpWhitelistExemption() {
        // 1. 本地回环地址默认豁免
        assertThat(memoryService.isIpWhiteListed("127.0.0.1")).isTrue();
        assertThat(memoryService.isIpWhiteListed("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(memoryService.isIpWhiteListed("::1")).isTrue();

        // 对白名单 IP 记录 50 次失败，不会累计也不会触发锁定
        for (int i = 0; i < 50; i++) {
            int remain = memoryService.recordIpFailure("127.0.0.1");
            assertThat(remain).isEqualTo(30);
        }
        assertThat(memoryService.isIpLocked("127.0.0.1")).isFalse();
        assertThat(memoryService.getIpFailureCount("127.0.0.1")).isEqualTo(0);
        memoryService.checkIpLocked("127.0.0.1");

        // 2. 自定义 CIDR 白名单
        properties.getLoginDefense().setIpWhiteList(List.of("192.168.1.0/24", "10.0.0.0/8"));
        assertThat(memoryService.isIpWhiteListed("192.168.1.50")).isTrue();
        assertThat(memoryService.isIpWhiteListed("10.254.1.1")).isTrue();
        assertThat(memoryService.isIpWhiteListed("192.168.2.50")).isFalse();
    }

    @Test
    @DisplayName("双轨防撞库协同防卫：单 IP 跨账号批量撞库触发 IP 临时熔断")
    void testDualTrackBruteForceProtection() {
        String attackerIp = "198.51.100.99";
        properties.getLoginDefense().setMaxRetryCount(3);
        properties.getLoginDefense().setIpMaxRetryCount(6);

        // 攻击者尝试第一个账号 victim_user_1 (输错 3 次锁定该账号)
        assertThat(memoryService.recordFailure("victim_user_1", attackerIp)).isEqualTo(2);
        assertThat(memoryService.recordFailure("victim_user_1", attackerIp)).isEqualTo(1);
        assertThat(memoryService.recordFailure("victim_user_1", attackerIp)).isEqualTo(0);
        assertThat(memoryService.isLocked("victim_user_1")).isTrue();
        assertThat(memoryService.isIpLocked(attackerIp)).isFalse();

        // 攻击者切换第二个账号 victim_user_2 (输错 3 次，累计 IP 达 6 次触发 IP 熔断)
        assertThat(memoryService.recordFailure("victim_user_2", attackerIp)).isEqualTo(2);
        assertThat(memoryService.recordFailure("victim_user_2", attackerIp)).isEqualTo(1);
        assertThat(memoryService.recordFailure("victim_user_2", attackerIp)).isEqualTo(0);
        assertThat(memoryService.isLocked("victim_user_2")).isTrue();

        // 此时 IP 达到 6 次被熔断
        assertThat(memoryService.isIpLocked(attackerIp)).isTrue();

        // 攻击者再尝试未被锁定的全新账号 victim_user_3，checkLocked 在 IP 维度即被 Fail-Fast 阻断
        assertThatThrownBy(() -> memoryService.checkLocked("victim_user_3", attackerIp))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户端 IP 连续登录失败次数超限，已被临时锁定");
    }

    @Test
    @DisplayName("防御性边界测试：入参 null、空白字符与无上下文请求安全防卫")
    void testDefensiveArguments() {
        assertThat(redisService.recordFailure(null)).isEqualTo(0);
        assertThat(redisService.recordFailure("  ")).isEqualTo(0);
        assertThat(redisService.recordFailure(null, null)).isEqualTo(0);
        assertThat(redisService.recordIpFailure(null)).isEqualTo(30);
        assertThat(redisService.recordIpFailure("  ")).isEqualTo(30);
        assertThat(redisService.getRemainRetryCount(null)).isEqualTo(0);
        assertThat(redisService.getFailureCount(null)).isEqualTo(0);
        assertThat(redisService.getIpFailureCount(null)).isEqualTo(0);
        assertThat(redisService.getIpRemainRetryCount(null)).isEqualTo(30);
        assertThat(redisService.isLocked(null)).isFalse();
        assertThat(redisService.isLocked(null, null)).isFalse();
        assertThat(redisService.isIpLocked(null)).isFalse();
        assertThat(redisService.isIpWhiteListed(null)).isFalse();
        assertThat(redisService.getDisableTime(null)).isEqualTo(0L);
        assertThat(redisService.getLockTime(null)).isEqualTo(0L);
        assertThat(redisService.getIpLockTime(null)).isEqualTo(0L);

        // 不应抛出任何异常
        redisService.clearFailure(null);
        redisService.clearIpFailure(null);
        redisService.clearAllFailure(null, null);
        redisService.checkLocked(null);
        redisService.checkLocked(null, null);
        redisService.checkIpLocked(null);
    }

    @Test
    @DisplayName("对抗性测试：单机内存模式防恶意海量撞库攻击导致内存无限膨胀")
    void testLocalMemoryCapacityDefense() {
        for (int i = 0; i < 50; i++) {
            memoryService.recordFailure("flood_user_" + i, "203.0.113." + (i % 250));
        }
        assertThat(memoryService.getLocalMemorySize()).isGreaterThan(0);
        memoryService.clearLocalMemory();
        assertThat(memoryService.getLocalMemorySize()).isEqualTo(0);
    }
}
