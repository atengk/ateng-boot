package io.github.atengk.boot.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 应用下线监听器 AppShutdownListener 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("应用下线监听器 AppShutdownListener 单测")
class AppShutdownListenerTest {

    @Test
    @DisplayName("Uptime 格式化输出算法验证")
    void shouldFormatUptimeCorrectly() {
        assertThat(AppShutdownListener.formatUptime(450)).isEqualTo("0.450s");
        assertThat(AppShutdownListener.formatUptime(65000)).isEqualTo("1m 5s");
        assertThat(AppShutdownListener.formatUptime(3665000)).isEqualTo("1h 1m 5s");
        assertThat(AppShutdownListener.formatUptime(0)).isEqualTo("0.000s");
    }

    @Test
    @DisplayName("容器关闭事件触发时安全执行下线日志打印")
    void shouldHandleContextClosedEvent() {
        AppShutdownListener listener = new AppShutdownListener(true);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            ContextClosedEvent event = new ContextClosedEvent(context);

            assertThatCode(() -> listener.onApplicationEvent(event)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("当禁用时忽略事件处理")
    void shouldBypassWhenDisabled() {
        AppShutdownListener listener = new AppShutdownListener(false);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            ContextClosedEvent event = new ContextClosedEvent(context);

            assertThatCode(() -> listener.onApplicationEvent(event)).doesNotThrowAnyException();
        }
    }
}
