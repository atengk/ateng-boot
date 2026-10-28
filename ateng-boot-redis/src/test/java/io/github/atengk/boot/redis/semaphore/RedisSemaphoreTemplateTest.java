package io.github.atengk.boot.redis.semaphore;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisSemaphoreTemplate 与 RedisSemaphoreUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("RedisSemaphoreTemplate 与 RedisSemaphoreUtil 单元测试")
class RedisSemaphoreTemplateTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RSemaphore semaphore = mock(RSemaphore.class);
    private RedisSemaphoreTemplate template;

    @BeforeEach
    void setUp() {
        when(redissonClient.getSemaphore(anyString())).thenReturn(semaphore);
        RedisProperties properties = new RedisProperties();
        template = new RedisSemaphoreTemplate(redissonClient, properties);
        RedisSemaphoreUtil.init(template);
    }

    @Test
    @DisplayName("测试成功申请许可证并在 finally 块中释放")
    void testExecuteSuccessAndRelease() throws Exception {
        when(semaphore.trySetPermits(5)).thenReturn(true);
        when(semaphore.tryAcquire(1, Duration.ofSeconds(2))).thenReturn(true);

        String result = template.execute("export:job", 5, Duration.ofSeconds(2), () -> "export_success");

        assertThat(result).isEqualTo("export_success");
        verify(semaphore).trySetPermits(5);
        verify(semaphore).tryAcquire(1, Duration.ofSeconds(2));
        verify(semaphore).release(1);
    }

    @Test
    @DisplayName("测试申请多许可证执行并在 finally 块中释放对应数量")
    void testExecuteMultiplePermits() throws Exception {
        when(semaphore.trySetPermits(10)).thenReturn(true);
        when(semaphore.tryAcquire(3, Duration.ofSeconds(1))).thenReturn(true);

        Integer result = template.execute("batch:task", 10, 3, Duration.ofSeconds(1), () -> 42);

        assertThat(result).isEqualTo(42);
        verify(semaphore).tryAcquire(3, Duration.ofSeconds(1));
        verify(semaphore).release(3);
    }

    @Test
    @DisplayName("测试业务闭包抛出异常时依然确定性释放许可证且透传异常")
    void testExecuteExceptionStillReleasesPermits() throws Exception {
        when(semaphore.tryAcquire(1)).thenReturn(true);

        assertThatThrownBy(() -> template.execute("error:flow", 3, () -> {
            throw new IllegalStateException("业务内部异常");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("业务内部异常");

        verify(semaphore).release(1);
    }

    @Test
    @DisplayName("测试争用超时且无降级时快速熔断抛出 429 业务异常")
    void testExecuteTimeoutThrows429() throws Exception {
        when(semaphore.tryAcquire(1, Duration.ofMillis(500))).thenReturn(false);

        assertThatThrownBy(() -> template.execute("busy:lock", 2, Duration.ofMillis(500), () -> "never"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
                });

        verify(semaphore, never()).release(anyInt());
    }

    @Test
    @DisplayName("测试争用超时时平滑执行降级闭包")
    void testExecuteTimeoutFallback() throws Exception {
        when(semaphore.tryAcquire(1, Duration.ofMillis(200))).thenReturn(false);

        String result = template.execute("heavy:task", 2, Duration.ofMillis(200),
                () -> "normal",
                () -> "fallback_degraded");

        assertThat(result).isEqualTo("fallback_degraded");
        verify(semaphore, never()).release(anyInt());
    }

    @Test
    @DisplayName("测试无返回值闭包与降级逻辑")
    void testExecuteActionWithFallback() throws Exception {
        when(semaphore.tryAcquire(1, Duration.ZERO)).thenReturn(false);
        boolean[] fallbackExecuted = new boolean[]{false};

        template.execute("action:flow", 5, Duration.ZERO,
                () -> {},
                () -> fallbackExecuted[0] = true);

        assertThat(fallbackExecuted[0]).isTrue();
    }

    @Test
    @DisplayName("测试配额运维调配方法")
    void testPermitsManagement() {
        when(semaphore.trySetPermits(anyInt())).thenReturn(true);
        when(semaphore.availablePermits()).thenReturn(8);
        when(semaphore.delete()).thenReturn(true);

        assertThat(template.trySetPermits("quota:test", 10)).isTrue();
        assertThat(template.availablePermits("quota:test")).isEqualTo(8);

        template.addPermits("quota:test", 5);
        verify(semaphore).addPermits(5);

        assertThat(template.resetPermits("quota:test", 20)).isTrue();
        verify(semaphore).delete();
        verify(semaphore).trySetPermits(20);

        assertThat(template.delete("quota:test")).isTrue();
    }

    @Test
    @DisplayName("测试全局静态门面 RedisSemaphoreUtil 委托调用")
    void testStaticUtilDelegation() throws Exception {
        when(semaphore.tryAcquire(1, Duration.ofSeconds(1))).thenReturn(true);

        String res = RedisSemaphoreUtil.execute("util:job", 3, Duration.ofSeconds(1), () -> "static_ok");

        assertThat(res).isEqualTo("static_ok");
        verify(semaphore).release(1);
    }

    @Test
    @DisplayName("测试 trySetPermits 底层网络异常时受控转译为 BusinessException")
    void testTrySetPermitsExceptionTranslation() {
        org.redisson.client.RedisException redisException = new org.redisson.client.RedisException("Redis node disconnected");
        when(semaphore.trySetPermits(5)).thenThrow(redisException);

        assertThatThrownBy(() -> template.execute("net:error", 5, () -> "never"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("获取分布式信号量底层异常")
                .hasCause(redisException);

        assertThatThrownBy(() -> template.trySetPermits("net:error", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("初始化分布式信号量配额底层异常")
                .hasCause(redisException);
    }

    @Test
    @DisplayName("测试前置参数防御性校验")
    void testValidation() {
        assertThatThrownBy(() -> template.execute("", 5, () -> "fail"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.execute("name", 0, () -> "fail"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.execute("name", 5, 0, Duration.ZERO, () -> "fail"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.execute("name", 5, 6, Duration.ZERO, () -> "fail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquirePermits must not exceed totalPermits");
    }
}
