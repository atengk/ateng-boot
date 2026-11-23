package io.github.atengk.boot.sample.admin.support;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 样例沙箱可重入锁与临时配置覆盖受控执行辅助工具
 * 统一规范“持锁校验后于 finally 释放”与单机演示属性临时切换后的确定性还原
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class SamplePropertyOverrideSupport {

    private SamplePropertyOverrideSupport() {
    }

    /**
     * 在可重入锁保护下执行受控操作，确保仅当当前线程持有锁时在 finally 中释放
     *
     * @param lock   可重入锁实例
     * @param action 受锁保护的业务逻辑
     * @param <R>    返回值类型
     * @return 业务逻辑执行结果
     */
    public static <R> R withLock(ReentrantLock lock, Supplier<R> action) {
        Objects.requireNonNull(lock, "lock 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        lock.lock();
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 在可重入锁保护下临时覆写演示配置属性，执行完成后于 finally 确定性还原原值并安全释放锁
     *
     * @param lock          可重入锁实例
     * @param getter        原配置值读取器
     * @param setter        配置值写入器
     * @param overrideValue 本次执行期临时覆盖值
     * @param action        受锁与临时配置保护的业务逻辑
     * @param <T>           配置属性类型
     * @param <R>           返回值类型
     * @return 业务逻辑执行结果
     */
    public static <T, R> R withTemporaryOverride(ReentrantLock lock,
                                                 Supplier<T> getter,
                                                 Consumer<T> setter,
                                                 T overrideValue,
                                                 Supplier<R> action) {
        Objects.requireNonNull(lock, "lock 不能为空");
        Objects.requireNonNull(getter, "getter 不能为空");
        Objects.requireNonNull(setter, "setter 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        lock.lock();
        T originalValue = getter.get();
        try {
            setter.accept(overrideValue);
            return action.get();
        } finally {
            setter.accept(originalValue);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
