package io.github.atengk.boot.redis.lock.template;

/**
 * 分布式锁无返回值执行闭包，支持带精确异常推导
 *
 * @param <E> 闭包声明抛出的异常类型
 * @author Ateng
 * @since 2026-10-16
 */
@FunctionalInterface
public interface LockAction<E extends Throwable> {

    /**
     * 执行受分布式锁保护的业务逻辑
     *
     * @throws E 业务逻辑抛出的异常
     */
    void run() throws E;
}
