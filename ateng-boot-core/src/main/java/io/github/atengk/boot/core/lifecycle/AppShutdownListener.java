package io.github.atengk.boot.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * 应用优雅下线生命周期监听器
 * 监听 ContextClosedEvent 事件，在 Spring 容器正常关闭时输出友好摘要与总运行时长 (Uptime)
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class AppShutdownListener implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AppShutdownListener.class);

    private final boolean enabled;

    public AppShutdownListener() {
        this(true);
    }

    public AppShutdownListener(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!enabled) {
            return;
        }
        // 仅处理根 ApplicationContext 关闭事件，忽略子 context
        if (event.getApplicationContext().getParent() != null) {
            return;
        }

        String appName = event.getApplicationContext().getEnvironment()
                .getProperty("spring.application.name", "application");
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String formattedUptime = formatUptime(uptimeMs);

        log.info("[ATENG] Application [{}] stopped gracefully. Total Uptime: {}", appName, formattedUptime);
    }

    /**
     * 格式化运行时长为可读字符串
     *
     * @param uptimeMs 毫秒数
     * @return 格式化时长（如 "2h 15m 30s" 或 "12.345s"）
     */
    public static String formatUptime(long uptimeMs) {
        Duration duration = Duration.ofMillis(Math.max(0, uptimeMs));
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%.3fs", uptimeMs / 1000.0);
    }
}
