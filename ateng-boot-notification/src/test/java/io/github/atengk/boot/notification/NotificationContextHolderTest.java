package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.context.NotificationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协同通知线程级动态切源上下文持有者单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("通知上下文持有者单元测试")
class NotificationContextHolderTest {

    @AfterEach
    void tearDown() {
        NotificationContextHolder.clear();
    }

    @Test
    @DisplayName("测试基础设置与清除上下文")
    void testBasicSetAndClear() {
        assertThat(NotificationContextHolder.getChannel()).isNull();

        NotificationContextHolder.setChannel("ops-channel");
        assertThat(NotificationContextHolder.getChannel()).isEqualTo("ops-channel");

        NotificationContextHolder.setChannel("   ");
        assertThat(NotificationContextHolder.getChannel()).isNull();

        NotificationContextHolder.setChannel("ops-channel");
        NotificationContextHolder.clear();
        assertThat(NotificationContextHolder.getChannel()).isNull();
    }

    @Test
    @DisplayName("测试 runWithChannel 执行并恢复上下文")
    void testRunWithChannel() {
        NotificationContextHolder.setChannel("outer-channel");

        NotificationContextHolder.runWithChannel("inner-channel", () -> {
            assertThat(NotificationContextHolder.getChannel()).isEqualTo("inner-channel");
        });

        // 验证执行完毕后恢复外层上下文
        assertThat(NotificationContextHolder.getChannel()).isEqualTo("outer-channel");

        // 异常退出时也能可靠恢复
        try {
            NotificationContextHolder.runWithChannel("failing-channel", () -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception ignored) {
        }
        assertThat(NotificationContextHolder.getChannel()).isEqualTo("outer-channel");
    }

    @Test
    @DisplayName("测试 callWithChannel 返回值并恢复上下文")
    void testCallWithChannel() throws Exception {
        NotificationContextHolder.clear();

        String result = NotificationContextHolder.callWithChannel("compute-channel", () -> {
            assertThat(NotificationContextHolder.getChannel()).isEqualTo("compute-channel");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(NotificationContextHolder.getChannel()).isNull();
    }

    @Test
    @DisplayName("测试线程间上下文绝对隔离")
    void testThreadIsolation() throws Exception {
        NotificationContextHolder.setChannel("main-thread-channel");

        AtomicReference<String> subThreadChannel = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread subThread = new Thread(() -> {
            subThreadChannel.set(NotificationContextHolder.getChannel());
            latch.countDown();
        });
        subThread.start();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(subThreadChannel.get()).isNull();
        assertThat(NotificationContextHolder.getChannel()).isEqualTo("main-thread-channel");
    }
}
