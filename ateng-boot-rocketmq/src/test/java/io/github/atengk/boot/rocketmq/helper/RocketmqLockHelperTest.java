package io.github.atengk.boot.rocketmq.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * RocketmqLockHelper 单元测试
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("RocketmqLockHelper 单元测试")
class RocketmqLockHelperTest {

    @Test
    @DisplayName("runWithLock 与 withLock：正常加解锁与可重入支持")
    void shouldAcquireAndReleaseReentrantLockSafely() {
        ReentrantLock lock = new ReentrantLock();

        RocketmqLockHelper.runWithLock(lock, () -> {
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            String nested = RocketmqLockHelper.withLock(lock, () -> {
                assertThat(lock.getHoldCount()).isEqualTo(2);
                return "ok";
            });
            assertThat(nested).isEqualTo("ok");
            assertThat(lock.getHoldCount()).isEqualTo(1);
        });

        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("临界区抛出异常时仍应确定性释放锁")
    void shouldUnlockWhenActionThrows() {
        ReentrantLock lock = new ReentrantLock();

        assertThatIllegalStateException()
                .isThrownBy(() -> RocketmqLockHelper.runWithLock(lock, () -> {
                    throw new IllegalStateException("boom");
                }))
                .withMessage("boom");
        assertThat(lock.isLocked()).isFalse();

        assertThatIllegalStateException()
                .isThrownBy(() -> RocketmqLockHelper.withLock(lock, () -> {
                    throw new IllegalStateException("boom-supplier");
                }))
                .withMessage("boom-supplier");
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("入参为空时快速失败拦截")
    void shouldFailFastOnNullArguments() {
        ReentrantLock lock = new ReentrantLock();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RocketmqLockHelper.runWithLock(null, () -> {
                }));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RocketmqLockHelper.runWithLock(lock, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RocketmqLockHelper.withLock(null, () -> "x"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RocketmqLockHelper.withLock(lock, null));
    }
}
