package io.github.atengk.boot.redis.lock.util;

import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.template.LockAction;
import io.github.atengk.boot.redis.lock.template.LockSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 静态门面 RedisLockUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisLockUtil 单元测试")
class RedisLockUtilTest {

    @Mock
    private DistributedLockTemplate lockTemplate;

    @BeforeEach
    void setUp() {
        RedisLockUtil.init(lockTemplate);
    }

    @AfterEach
    void tearDown() {
        RedisLockUtil.init(null);
    }

    @Test
    @DisplayName("未初始化时调用静态方法应抛出 IllegalStateException")
    void shouldThrowIllegalStateExceptionWhenUninitialized() {
        RedisLockUtil.init(null);
        assertThatThrownBy(() -> RedisLockUtil.execute("k1", () -> "val"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DistributedLockTemplate尚未初始化");
    }

    @Test
    @DisplayName("execute(supplier): 静态门面正确委托模板调用")
    void execute_supplier_delegates() throws Throwable {
        when(lockTemplate.execute(eq("order:1"), any(LockSupplier.class))).thenReturn("RESULT_OK");

        String result = RedisLockUtil.execute("order:1", () -> "RESULT_OK");

        assertThat(result).isEqualTo("RESULT_OK");
        verify(lockTemplate).execute(eq("order:1"), any(LockSupplier.class));
    }

    @Test
    @DisplayName("execute(action): 静态门面正确委托模板调用")
    void execute_action_delegates() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        RedisLockUtil.execute("order:2", () -> ran.set(true));
        verify(lockTemplate).execute(eq("order:2"), any(LockAction.class));
    }

    @Test
    @DisplayName("execute(fallback): 静态门面正确委托降级调用")
    void execute_fallback_delegates() throws Throwable {
        when(lockTemplate.execute(eq("order:3"), any(LockSupplier.class), any(LockSupplier.class))).thenReturn("FALLBACK_VAL");

        String result = RedisLockUtil.execute("order:3", () -> "PRIMARY", () -> "FALLBACK_VAL");

        assertThat(result).isEqualTo("FALLBACK_VAL");
        verify(lockTemplate).execute(eq("order:3"), any(LockSupplier.class), any(LockSupplier.class));
    }

    @Test
    @DisplayName("tryExecute: 静态门面正确委托试探调用")
    void tryExecute_delegates() throws Throwable {
        when(lockTemplate.tryExecute(eq("job:1"), any(LockAction.class))).thenReturn(true);

        boolean ok = RedisLockUtil.tryExecute("job:1", () -> {});

        assertThat(ok).isTrue();
        verify(lockTemplate).tryExecute(eq("job:1"), any(LockAction.class));
    }

    @Test
    @DisplayName("execute(multiKeys): 静态门面正确委托全参调用")
    void execute_multiKeys_delegates() throws Throwable {
        when(lockTemplate.execute(eq(List.of("k1", "k2")), eq(LockMode.FAIR), eq(1000L), eq(5000L), eq(TimeUnit.MILLISECONDS), any(LockSupplier.class)))
                .thenReturn("MULTI_OK");

        String res = RedisLockUtil.execute(List.of("k1", "k2"), LockMode.FAIR, 1000L, 5000L, TimeUnit.MILLISECONDS, () -> "MULTI_OK");

        assertThat(res).isEqualTo("MULTI_OK");
        verify(lockTemplate).execute(eq(List.of("k1", "k2")), eq(LockMode.FAIR), eq(1000L), eq(5000L), eq(TimeUnit.MILLISECONDS), any(LockSupplier.class));
    }
}
