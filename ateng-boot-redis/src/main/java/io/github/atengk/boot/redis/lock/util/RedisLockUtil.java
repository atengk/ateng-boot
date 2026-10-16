package io.github.atengk.boot.redis.lock.util;

import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.template.LockAction;
import io.github.atengk.boot.redis.lock.template.LockSupplier;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 生产级编程式分布式锁静态操作门面
 * 底层统一委托 {@link DistributedLockTemplate}，支持在静态上下文或无需依赖注入的场景下一键安全加锁
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class RedisLockUtil {

    private static volatile DistributedLockTemplate lockTemplate;

    private RedisLockUtil() {
    }

    /**
     * 初始化模板单例（由自动装配生命周期调用）
     *
     * @param template 分布式锁模板
     */
    public static void init(DistributedLockTemplate template) {
        RedisLockUtil.lockTemplate = template;
    }

    /**
     * 获取受管模板实例，未就绪时抛出 IllegalStateException
     */
    private static DistributedLockTemplate getTemplate() {
        DistributedLockTemplate template = lockTemplate;
        if (template == null) {
            throw new IllegalStateException("DistributedLockTemplate尚未初始化，请确保应用已启用分布式锁自动装配");
        }
        return template;
    }

    /**
     * 最简加锁执行闭包（带返回值，默认可重入排他锁，继承全局超时与看门狗配置）
     *
     * @param key 业务锁键
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务计算结果
     * @throws E 业务抛出的异常
     */
    public static <T, E extends Throwable> T execute(String key, LockSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, supplier);
    }

    /**
     * 最简加锁执行闭包（无返回值，默认可重入排他锁，继承全局超时与看门狗配置）
     *
     * @param key 业务锁键
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @throws E 业务抛出的异常
     */
    public static <E extends Throwable> void execute(String key, LockAction<E> action) throws E {
        getTemplate().execute(key, action);
    }

    /**
     * 带降级回调的加锁执行闭包（获取锁失败时不抛异常，执行 fallback）
     *
     * @param key 业务锁键
     * @param supplier 受保护业务闭包
     * @param fallback 争锁失败降级闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务计算结果或降级结果
     * @throws E 业务抛出的异常
     */
    public static <T, E extends Throwable> T execute(String key, LockSupplier<T, E> supplier, LockSupplier<T, E> fallback) throws E {
        return getTemplate().execute(key, supplier, fallback);
    }

    /**
     * 带降级回调的无返回值加锁执行闭包
     *
     * @param key 业务锁键
     * @param action 受保护业务闭包
     * @param fallback 争锁失败降级闭包
     * @param <E> 异常类型
     * @throws E 业务抛出的异常
     */
    public static <E extends Throwable> void execute(String key, LockAction<E> action, LockAction<E> fallback) throws E {
        getTemplate().execute(key, action, fallback);
    }

    /**
     * 单键自定义超时与锁模式加锁执行（带返回值）
     */
    public static <T, E extends Throwable> T execute(String key, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, mode, waitTime, leaseTime, timeUnit, supplier);
    }

    /**
     * 单键自定义超时与锁模式加锁执行（无返回值）
     */
    public static <E extends Throwable> void execute(String key, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        getTemplate().execute(key, mode, waitTime, leaseTime, timeUnit, action);
    }

    /**
     * 多键联锁加锁执行（带返回值）
     */
    public static <T, E extends Throwable> T execute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockSupplier<T, E> supplier) throws E {
        return getTemplate().execute(keys, mode, waitTime, leaseTime, timeUnit, supplier);
    }

    /**
     * 多键联锁加锁执行（无返回值）
     */
    public static <E extends Throwable> void execute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        getTemplate().execute(keys, mode, waitTime, leaseTime, timeUnit, action);
    }

    /**
     * 争锁试探执行（缺省等待时间）：争锁成功执行闭包并返回 true；争锁超时不抛异常直接返回 false
     *
     * @param key 业务锁键
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @return 是否成功获取锁并执行
     * @throws E 业务抛出的异常
     */
    public static <E extends Throwable> boolean tryExecute(String key, LockAction<E> action) throws E {
        return getTemplate().tryExecute(key, action);
    }

    /**
     * 争锁试探执行（自定义等待时间）：争锁成功执行闭包并返回 true；争锁超时不抛异常直接返回 false
     *
     * @param key 业务锁键
     * @param waitTime 等待时长
     * @param timeUnit 时间单位
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @return 是否成功获取锁并执行
     * @throws E 业务抛出的异常
     */
    public static <E extends Throwable> boolean tryExecute(String key, long waitTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        return getTemplate().tryExecute(key, waitTime, timeUnit, action);
    }

    /**
     * 多键联锁争锁试探执行
     */
    public static <E extends Throwable> boolean tryExecute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        return getTemplate().tryExecute(keys, mode, waitTime, leaseTime, timeUnit, action);
    }
}
