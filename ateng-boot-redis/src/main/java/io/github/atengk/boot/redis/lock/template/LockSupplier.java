package io.github.atengk.boot.redis.lock.template;

/**
 * 分布式锁计算型函数式闭包，支持带泛型类型与精确异常推导
 *
 * @param <T> 返回值泛型
 * @param <E> 闭包声明抛出的异常类型
 * @author Ateng
 * @since 2026-10-16
 */
@FunctionalInterface
public interface LockSupplier<T, E extends Throwable> {

    /**
     * 执行受分布式锁保护的业务逻辑并返回计算结果
     *
     * @return 业务执行结果
     * @throws E 业务逻辑抛出的异常
     */
    T get() throws E;
}
