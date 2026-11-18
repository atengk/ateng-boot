package io.github.atengk.boot.mqtt.util;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * MQTT 内部守护任务调度器工厂辅助类
 * 统一构造具备一致线程前缀、守护标志与非阻塞停机策略的 {@link ThreadPoolTaskScheduler} 实例
 *
 * @author Ateng
 * @since 2026-11-18
 */
public final class MqttSchedulerSupport {

    private MqttSchedulerSupport() {
    }

    /**
     * 创建并初始化守护线程 {@link ThreadPoolTaskScheduler}
     *
     * @param poolSize         核心调度线程数
     * @param threadNamePrefix 线程名称前缀
     * @return 已初始化的受管调度器实例
     */
    public static ThreadPoolTaskScheduler createDaemonScheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
