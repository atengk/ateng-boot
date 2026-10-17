package io.github.atengk.boot.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命名线程工厂单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("NamedThreadFactory 单元测试")
class NamedThreadFactoryTest {

    @Test
    @DisplayName("测试基础自增线程命名与默认非守护属性")
    void testBasicThreadNaming() {
        NamedThreadFactory factory = new NamedThreadFactory("order-worker");
        assertThat(factory.getPrefix()).isEqualTo("order-worker");
        assertThat(factory.isDaemon()).isFalse();

        Thread t1 = factory.newThread(() -> {});
        Thread t2 = factory.newThread(() -> {});

        assertThat(t1.getName()).isEqualTo("order-worker-1");
        assertThat(t2.getName()).isEqualTo("order-worker-2");
        assertThat(t1.isDaemon()).isFalse();
        assertThat(t2.isDaemon()).isFalse();
        assertThat(t1.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
    }

    @Test
    @DisplayName("测试末尾带横杠前缀的命名处理与守护线程设置")
    void testDaemonAndHyphenPrefix() {
        NamedThreadFactory factory = new NamedThreadFactory("cron-sync-", true);
        assertThat(factory.isDaemon()).isTrue();

        Thread t1 = factory.newThread(() -> {});
        assertThat(t1.getName()).isEqualTo("cron-sync-1");
        assertThat(t1.isDaemon()).isTrue();
    }

    @Test
    @DisplayName("测试空前缀兜底为默认前缀")
    void testFallbackPrefix() {
        NamedThreadFactory factory1 = new NamedThreadFactory(null);
        NamedThreadFactory factory2 = new NamedThreadFactory("   ");

        assertThat(factory1.getPrefix()).isEqualTo("ateng-pool");
        assertThat(factory2.getPrefix()).isEqualTo("ateng-pool");

        Thread t1 = factory1.newThread(() -> {});
        assertThat(t1.getName()).isEqualTo("ateng-pool-1");
    }

    @Test
    @DisplayName("测试自定义未捕获异常处理器生效")
    void testUncaughtExceptionHandler() throws InterruptedException {
        AtomicReference<Throwable> capturedEx = new AtomicReference<>();
        AtomicReference<String> capturedThreadName = new AtomicReference<>();

        NamedThreadFactory factory = new NamedThreadFactory("error-worker", false, (thread, throwable) -> {
            capturedThreadName.set(thread.getName());
            capturedEx.set(throwable);
        });

        Thread thread = factory.newThread(() -> {
            throw new RuntimeException("测试未捕获异常");
        });

        thread.start();
        thread.join(2000);

        assertThat(capturedThreadName.get()).isEqualTo("error-worker-1");
        assertThat(capturedEx.get()).isInstanceOf(RuntimeException.class).hasMessage("测试未捕获异常");
    }

    @Test
    @DisplayName("测试任务为 null 时抛出空指针异常")
    void testNullRunnable() {
        NamedThreadFactory factory = new NamedThreadFactory("test");
        assertThatThrownBy(() -> factory.newThread(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Runnable task must not be null");
    }
}
