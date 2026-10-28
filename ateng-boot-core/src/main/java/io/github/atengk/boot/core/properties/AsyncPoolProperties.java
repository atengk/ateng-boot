package io.github.atengk.boot.core.properties;

import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.enums.AsyncRejectedPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 异步线程池运行时增强配置属性实体
 * 统管单个受管线程池的容量、队列缓冲、优雅停机、执行模式与饱和拒绝策略
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@Setter
public class AsyncPoolProperties {

    /**
     * 是否启用当前线程池（默认 true）
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
     * 空闲线程存活时间（强类型 Duration，默认 60 秒）
     */
    private Duration keepAlive = Duration.ofSeconds(60);

    /**
     * 异步工作线程名前缀（若未显式配置，在注册时按规则自动推导）
     */
    private String threadNamePrefix;

    /**
     * 虚拟线程工作线程名前缀（若未显式配置，在注册时按规则自动推导）
     */
    private String virtualThreadNamePrefix;

    /**
     * 虚拟线程并发任务限制数（默认 -1，表示无界/不设限制）
     */
    private int concurrencyLimit = -1;

    /**
     * 容器关闭时是否等待所有已提交的异步任务执行完成（优雅停机，默认 true）
     */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /**
     * 容器关闭时等待任务执行完成的最大超时时间（强类型 Duration，默认 60 秒）
     */
    private Duration awaitTermination = Duration.ofSeconds(60);

    /**
     * 线程运行模式（默认 AUTO，跟随全局环境自适应推导）
     */
    private AsyncExecutionMode mode = AsyncExecutionMode.AUTO;

    /**
     * 平台线程池任务饱和拒绝策略（默认 CALLER_RUNS）
     */
    private AsyncRejectedPolicy rejectedPolicy = AsyncRejectedPolicy.CALLER_RUNS;

    /**
     * 获取空闲线程存活时间（秒，向后兼容）
     *
     * @return 存活秒数
     */
    public int getKeepAliveSeconds() {
        return keepAlive != null ? (int) keepAlive.toSeconds() : 60;
    }

    /**
     * 设置空闲线程存活时间（秒，向后兼容）
     *
     * @param keepAliveSeconds 存活秒数
     */
    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAlive = Duration.ofSeconds(keepAliveSeconds);
    }

    /**
     * 获取容器关闭等待超时时间（秒，向后兼容）
     *
     * @return 超时秒数
     */
    public int getAwaitTerminationSeconds() {
        return awaitTermination != null ? (int) awaitTermination.toSeconds() : 60;
    }

    /**
     * 设置容器关闭等待超时时间（秒，向后兼容）
     *
     * @param awaitTerminationSeconds 超时秒数
     */
    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTermination = Duration.ofSeconds(awaitTerminationSeconds);
    }

    /**
     * 解析平台线程名前缀（未显式配置时根据池名称智能推导）
     *
     * @param poolName 线程池名称
     * @return 线程名前缀
     */
    public String resolveThreadNamePrefix(String poolName) {
        if (StringUtils.hasText(this.threadNamePrefix)) {
            return this.threadNamePrefix;
        }
        return StringUtils.hasText(poolName) ? "ateng-async-" + poolName + "-" : "ateng-async-";
    }

    /**
     * 解析虚拟线程名前缀（未显式配置时根据池名称智能推导）
     *
     * @param poolName 线程池名称
     * @return 虚拟线程名前缀
     */
    public String resolveVirtualThreadNamePrefix(String poolName) {
        if (StringUtils.hasText(this.virtualThreadNamePrefix)) {
            return this.virtualThreadNamePrefix;
        }
        return StringUtils.hasText(poolName) ? "ateng-vt-async-" + poolName + "-" : "ateng-vt-async-";
    }
}
