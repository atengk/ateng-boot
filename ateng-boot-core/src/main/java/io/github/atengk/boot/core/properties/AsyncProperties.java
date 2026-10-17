package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步线程池运行时增强配置属性
 * 提供基于 CPU 核心数自适应的受管线程池容量、队列缓冲与优雅停机策略控制
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.async")
public class AsyncProperties {

    /**
     * 是否启用上下文感知异步线程池自动装配
     */
    private boolean enabled = true;

    /**
     * 核心线程数（默认可用 CPU 核心数）
     */
    private int corePoolSize = Math.max(1, Runtime.getRuntime().availableProcessors());

    /**
     * 最大线程数（默认可用 CPU 核心数的两倍）
     */
    private int maxPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors()) * 2;

    /**
     * 阻塞队列容量（默认 1000）
     */
    private int queueCapacity = 1000;

    /**
     * 空闲线程存活时间（单位：秒，默认 60 秒）
     */
    private int keepAliveSeconds = 60;

    /**
     * 异步工作线程名前缀（默认 ateng-async-）
     */
    private String threadNamePrefix = "ateng-async-";

    /**
     * 虚拟线程工作线程名前缀（默认 ateng-vt-async-）
     */
    private String virtualThreadNamePrefix = "ateng-vt-async-";

    /**
     * 虚拟线程并发任务限制数（默认 -1，表示无界/不设限制）
     */
    private int concurrencyLimit = -1;

    /**
     * 容器关闭时是否等待所有已提交的异步任务执行完成（优雅停机，默认 true）
     */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /**
     * 容器关闭时等待任务执行完成的最大超时时间（单位：秒，默认 60 秒）
     */
    private int awaitTerminationSeconds = 60;
}
