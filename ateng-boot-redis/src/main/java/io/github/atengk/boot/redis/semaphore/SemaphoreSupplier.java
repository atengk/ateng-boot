package io.github.atengk.boot.redis.semaphore;

/**
 * 分布式信号量带返回值受保护业务闭包
 *
 * @param <T> 返回值类型
 * @param <E> 抛出异常类型
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface SemaphoreSupplier<T, E extends Throwable> {

    /**
     * 执行业务计算逻辑
     *
     * @return 业务结果
     * @throws E 允许抛出受控业务异常
     */
    T get() throws E;
}
