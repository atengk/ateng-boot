package io.github.atengk.boot.core.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 命名线程工厂标准件
 * 为线程池创建具备明确业务语义名称的线程，支持自定义前缀、守护线程标记与全局未捕获异常处理器，
 * 彻底杜绝线上线程转储 (jstack) 中无意义的 pool-N-thread-M 命名
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final String DEFAULT_PREFIX = "ateng-pool";

    private final String prefix;
    private final boolean daemon;
    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final ThreadGroup group;

    /**
     * 构造非守护命名线程工厂
     *
     * @param prefix 线程名前缀（如 "ateng-task"）
     */
    public NamedThreadFactory(String prefix) {
        this(prefix, false, null);
    }

    /**
     * 构造指定守护属性的命名线程工厂
     *
     * @param prefix 线程名前缀
     * @param daemon 是否为守护线程
     */
    public NamedThreadFactory(String prefix, boolean daemon) {
        this(prefix, daemon, null);
    }

    /**
     * 构造全功能命名线程工厂
     *
     * @param prefix                   线程名前缀
     * @param daemon                   是否为守护线程
     * @param uncaughtExceptionHandler 未捕获异常处理器（可为 null）
     */
    public NamedThreadFactory(String prefix, boolean daemon, Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.prefix = (prefix != null && !prefix.isBlank()) ? prefix.strip() : DEFAULT_PREFIX;
        this.daemon = daemon;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
        this.group = Thread.currentThread().getThreadGroup();
    }

    @Override
    public Thread newThread(Runnable r) {
        Objects.requireNonNull(r, "Runnable task must not be null");
        String threadName = prefix.endsWith("-")
                ? prefix + threadNumber.getAndIncrement()
                : prefix + "-" + threadNumber.getAndIncrement();

        Thread thread = new Thread(group, r, threadName, 0);
        thread.setDaemon(daemon);
        thread.setPriority(Thread.NORM_PRIORITY);

        if (uncaughtExceptionHandler != null) {
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        }

        return thread;
    }

    /**
     * 获取配置的线程名前缀
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * 是否为守护线程
     */
    public boolean isDaemon() {
        return daemon;
    }
}
