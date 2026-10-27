package io.github.atengk.boot.redis.semaphore;

import org.redisson.api.RSemaphore;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * 分布式信号量全局静态操作门面工具类
 * 静态委托给受管单例 RedisSemaphoreTemplate，提供无侵入快速调用范式
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class RedisSemaphoreUtil {

    private static volatile RedisSemaphoreTemplate staticTemplate;

    private RedisSemaphoreUtil() {
    }

    /**
     * 容器初始化绑定受管模板实例
     *
     * @param template 受管信号量模板
     */
    public static void init(RedisSemaphoreTemplate template) {
        Assert.notNull(template, "RedisSemaphoreTemplate must not be null");
        staticTemplate = template;
    }

    /**
     * 单许可证非阻塞受管闭包（快速失败，无降级）
     */
    public static <T, E extends Throwable> T execute(String name, int totalPermits, SemaphoreSupplier<T, E> supplier) throws E {
        return getTemplate().execute(name, totalPermits, supplier);
    }

    /**
     * 单许可证带超时等待受管闭包（无降级）
     */
    public static <T, E extends Throwable> T execute(String name, int totalPermits, Duration waitTimeout,
                                                     SemaphoreSupplier<T, E> supplier) throws E {
        return getTemplate().execute(name, totalPermits, waitTimeout, supplier);
    }

    /**
     * 单许可证带超时等待与降级回调受管闭包
     */
    public static <T, E extends Throwable> T execute(String name, int totalPermits, Duration waitTimeout,
                                                     SemaphoreSupplier<T, E> supplier,
                                                     SemaphoreSupplier<T, E> fallback) throws E {
        return getTemplate().execute(name, totalPermits, waitTimeout, supplier, fallback);
    }

    /**
     * 多许可证带超时等待受管闭包（无降级）
     */
    public static <T, E extends Throwable> T execute(String name, int totalPermits, int acquirePermits,
                                                     Duration waitTimeout,
                                                     SemaphoreSupplier<T, E> supplier) throws E {
        return getTemplate().execute(name, totalPermits, acquirePermits, waitTimeout, supplier);
    }

    /**
     * 全参数带返回值受管信号量执行闭包
     */
    public static <T, E extends Throwable> T execute(String name, int totalPermits, int acquirePermits,
                                                     Duration waitTimeout,
                                                     SemaphoreSupplier<T, E> supplier,
                                                     SemaphoreSupplier<T, E> fallback) throws E {
        return getTemplate().execute(name, totalPermits, acquirePermits, waitTimeout, supplier, fallback);
    }

    /**
     * 单许可证非阻塞受管闭包（无返回值，快速失败）
     */
    public static <E extends Throwable> void execute(String name, int totalPermits, SemaphoreAction<E> action) throws E {
        getTemplate().execute(name, totalPermits, action);
    }

    /**
     * 单许可证带超时等待受管闭包（无返回值）
     */
    public static <E extends Throwable> void execute(String name, int totalPermits, Duration waitTimeout,
                                                     SemaphoreAction<E> action) throws E {
        getTemplate().execute(name, totalPermits, waitTimeout, action);
    }

    /**
     * 单许可证带超时等待与降级回调受管闭包（无返回值）
     */
    public static <E extends Throwable> void execute(String name, int totalPermits, Duration waitTimeout,
                                                     SemaphoreAction<E> action,
                                                     SemaphoreAction<E> fallback) throws E {
        getTemplate().execute(name, totalPermits, waitTimeout, action, fallback);
    }

    /**
     * 多许可证带超时等待受管闭包（无返回值）
     */
    public static <E extends Throwable> void execute(String name, int totalPermits, int acquirePermits,
                                                     Duration waitTimeout,
                                                     SemaphoreAction<E> action) throws E {
        getTemplate().execute(name, totalPermits, acquirePermits, waitTimeout, action);
    }

    /**
     * 全参数无返回值受管信号量执行闭包
     */
    public static <E extends Throwable> void execute(String name, int totalPermits, int acquirePermits,
                                                     Duration waitTimeout,
                                                     SemaphoreAction<E> action,
                                                     SemaphoreAction<E> fallback) throws E {
        getTemplate().execute(name, totalPermits, acquirePermits, waitTimeout, action, fallback);
    }

    /**
     * 原子尝试初始化信号量许可证配额（仅在未设置时生效）
     */
    public static boolean trySetPermits(String name, int totalPermits) {
        return getTemplate().trySetPermits(name, totalPermits);
    }

    /**
     * 为信号量动态增补许可证配额
     */
    public static void addPermits(String name, int permits) {
        getTemplate().addPermits(name, permits);
    }

    /**
     * 强行重置信号量许可证总量配额
     */
    public static boolean resetPermits(String name, int totalPermits) {
        return getTemplate().resetPermits(name, totalPermits);
    }

    /**
     * 获取指定信号量当前剩余可用许可证数
     */
    public static int availablePermits(String name) {
        return getTemplate().availablePermits(name);
    }

    /**
     * 销毁并删除指定信号量
     */
    public static boolean delete(String name) {
        return getTemplate().delete(name);
    }

    /**
     * 获取底层原生 RSemaphore 实例
     */
    public static RSemaphore getSemaphore(String name) {
        return getTemplate().getSemaphore(name);
    }

    private static RedisSemaphoreTemplate getTemplate() {
        RedisSemaphoreTemplate template = staticTemplate;
        Assert.state(template != null,
                "RedisSemaphoreUtil has not been initialized. Please ensure AtengRedisAutoConfiguration is loaded.");
        return template;
    }
}
