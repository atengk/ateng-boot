package io.github.atengk.boot.redis.idempotent;

/**
 * 分布式防重幂等无返回值受保护业务闭包
 *
 * @param <E> 抛出异常类型
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface IdempotentAction<E extends Throwable> {

    /**
     * 执行业务操作逻辑
     *
     * @throws E 允许抛出的受控业务异常
     */
    void run() throws E;
}
