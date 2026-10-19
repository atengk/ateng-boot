package io.github.atengk.boot.satoken.defense;

import cn.dev33.satoken.stp.StpUtil;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LoginFailureLockService 单元测试
 * 覆盖密码连续输错计数、达到阈值自动封禁、登录成功重置、剩余次数查询及 Redis/内存双模自适应降级
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

    private SaTokenProperties properties;
    private LoginFailureLockService redisService;
    private LoginFailureLockService memoryService;

    private static final String TEST_ACCOUNT = "admin_attacker";

    @BeforeEach
    void setUp() {
        properties = new SaTokenProperties();
        properties.getLoginDefense().setEnabled(true);
        properties.getLoginDefense().setMaxRetryCount(5);
        properties.getLoginDefense().setLockTime(Duration.ofMinutes(15));
        properties.getLoginDefense().setWindowTime(Duration.ofMinutes(15));

        // 1. Redis 模式实例
        redisService = new LoginFailureLockService(redisTemplateProvider, properties);

        // 2. 纯内存降级模式实例（传入 null Provider 触发降级分支）
        memoryService = new LoginFailureLockService(null, properties);
    }

    @AfterEach
    void tearDown() {
        // 上下文零污染：解封测试账号并清空本地内存
        try {
            LoginHelper.untieDisable(TEST_ACCOUNT);
            LoginHelper.untieDisable("mem_user");
        } catch (Exception ignored) {
        }
        redisService.clearLocalMemory();
        memoryService.clearLocalMemory();
    }

    @Test
    @DisplayName("Redis 模式：输错密码原子递增并在达到阈值时触发封禁")
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
    @DisplayName("内存降级模式：连续输错递增并在第 5 次自动触发封禁")
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
    @DisplayName("内存降级模式：登录成功后清空计数")
    void testMemoryModeClearFailure() {
        String account = "mem_user_clean";
        memoryService.recordFailure(account);
        memoryService.recordFailure(account);
        assertThat(memoryService.getFailureCount(account)).isEqualTo(2);

        memoryService.clearFailure(account);
        assertThat(memoryService.getFailureCount(account)).isEqualTo(0);
        assertThat(memoryService.getRemainRetryCount(account)).isEqualTo(5);
    }

    @Test
    @DisplayName("内存降级模式：过期时间戳惰性剔除验证")
    void testMemoryModeExpiration() throws InterruptedException {
        // 设置极短的过期时间窗口 10ms
        properties.getLoginDefense().setWindowTime(Duration.ofMillis(10));
        String account = "mem_user_expire";

        memoryService.recordFailure(account);
        assertThat(memoryService.getFailureCount(account)).isEqualTo(1);

        // 睡眠等待过期
        Thread.sleep(20);

        // 过期后读取计数应自动为 0
        assertThat(memoryService.getFailureCount(account)).isEqualTo(0);
        assertThat(memoryService.getRemainRetryCount(account)).isEqualTo(5);
    }

    @Test
    @DisplayName("前置防御卫语句：入参 null 或空白字符安全防卫")
    void testDefensiveArguments() {
        assertThat(redisService.recordFailure(null)).isEqualTo(0);
        assertThat(redisService.recordFailure("  ")).isEqualTo(0);
        assertThat(redisService.getRemainRetryCount(null)).isEqualTo(0);
        assertThat(redisService.getFailureCount(null)).isEqualTo(0);
        assertThat(redisService.isLocked(null)).isFalse();
        assertThat(redisService.getDisableTime(null)).isEqualTo(0L);
        assertThat(redisService.getLockTime(null)).isEqualTo(0L);

        // 不应抛出任何异常
        redisService.clearFailure(null);
        redisService.checkLocked(null);
    }
}
