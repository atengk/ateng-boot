package io.github.atengk.boot.ai.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 活跃流式连接并发注册表单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class ActiveStreamRegistryTest {

    @Test
    @DisplayName("正常生命周期：注册、探测状态、主动取消与计数变更")
    void testRegisterAndCancel() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        registry.register("stream-100", () -> cancelled.set(true));

        assertThat(registry.isActive("stream-100")).isTrue();
        assertThat(registry.getActiveCount()).isEqualTo(1);
        assertThat(cancelled.get()).isFalse();

        boolean result = registry.cancel("stream-100");
        assertThat(result).isTrue();
        assertThat(cancelled.get()).isTrue();
        assertThat(registry.isActive("stream-100")).isFalse();
        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("竞态防卫：在注册前先触发取消信号，注册时应立即就地熔断切断")
    void testCancelBeforeRegisterRaceCondition() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // 客户端在建连完成前提前断开连接
        boolean initialCancel = registry.cancel("stream-early");
        assertThat(initialCancel).isFalse();

        // 此时底层 OkHttp Call 刚完成初始化并尝试注册
        registry.register("stream-early", () -> cancelled.set(true));

        // 验证由于此前已收到取消信号，注册时立即触发物理切断回调
        assertThat(cancelled.get()).isTrue();
        assertThat(registry.isActive("stream-early")).isFalse();
        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("正常注销：调用 unregister 移出注册表且不触发取消回调")
    void testUnregister() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        registry.register("stream-200", () -> cancelled.set(true));
        registry.unregister("stream-200");

        assertThat(registry.isActive("stream-200")).isFalse();
        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(cancelled.get()).isFalse();

        // 注销后再 cancel 返回 false
        assertThat(registry.cancel("stream-200")).isFalse();
    }

    @Test
    @DisplayName("幂等取消：多次调用 cancel 仅执行一次底层回调")
    void testMultipleCancellationsIdempotent() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicInteger count = new AtomicInteger(0);

        registry.register("stream-300", count::incrementAndGet);

        assertThat(registry.cancel("stream-300")).isTrue();
        assertThat(registry.cancel("stream-300")).isFalse();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("批量排空：clear 安全触发所有活跃连接的切断回调并清空集合")
    void testClear() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        AtomicInteger count = new AtomicInteger(0);

        registry.register("stream-a", count::incrementAndGet);
        registry.register("stream-b", count::incrementAndGet);
        registry.register("stream-c", count::incrementAndGet);

        assertThat(registry.getActiveCount()).isEqualTo(3);

        registry.clear();

        assertThat(registry.getActiveCount()).isEqualTo(0);
        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("防御性边界测试：空入参和空句柄安全忽略")
    void testDefensiveArgs() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();

        assertThatCode(() -> {
            registry.register(null, () -> {});
            registry.register("  ", () -> {});
            registry.register("stream-x", null);
            registry.cancel(null);
            registry.cancel("  ");
            registry.unregister(null);
            registry.unregister("  ");
            registry.isActive(null);
            registry.isActive("  ");
        }).doesNotThrowAnyException();

        assertThat(registry.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("容错测试：取消回调抛出异常时不影响注册表与外部链路")
    void testCancellerThrowsExceptionIsCaughtSafely() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();

        registry.register("stream-err", () -> {
            throw new RuntimeException("底层套接字强制关闭异常");
        });

        assertThatCode(() -> registry.cancel("stream-err")).doesNotThrowAnyException();
        assertThat(registry.isActive("stream-err")).isFalse();
    }
}
