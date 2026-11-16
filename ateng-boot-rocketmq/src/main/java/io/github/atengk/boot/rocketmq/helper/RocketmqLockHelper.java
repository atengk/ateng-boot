package io.github.atengk.boot.rocketmq.helper;

import org.springframework.util.Assert;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * RocketMQ 并发锁安全执行辅助工具类
 *
 * <p>统一封装 {@link ReentrantLock} 的加锁与 {@code finally} 持有状态判定释放范式，
 * 避免 JDK 21+ 虚拟线程环境下使用 {@code synchronized} 引发 Carrier Pinning 并消除跨类锁模板重复。</p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public final class RocketmqLockHelper {

    private RocketmqLockHelper() {
    }

    /**
     * 在显式可重入锁保护下执行无返回值操作，并在 {@code finally} 中按当前线程持有状态安全释放
     *
     * @param lock   可重入锁实例（不可为 {@code null}）
     * @param action 待执行逻辑（不可为 {@code null}）
     */
    public static void runWithLock(ReentrantLock lock, Runnable action) {
        Assert.notNull(lock, "lock 不能为空");
        Assert.notNull(action, "action 不能为空");
        lock.lock();
        try {
            action.run();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 在显式可重入锁保护下执行有返回值操作，并在 {@code finally} 中按当前线程持有状态安全释放
     *
     * @param lock   可重入锁实例（不可为 {@code null}）
     * @param action 待执行逻辑（不可为 {@code null}）
     * @param <T>    返回值类型
     * @return 执行结果
     */
    public static <T> T withLock(ReentrantLock lock, Supplier<T> action) {
        Assert.notNull(lock, "lock 不能为空");
        Assert.notNull(action, "action 不能为空");
        lock.lock();
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
