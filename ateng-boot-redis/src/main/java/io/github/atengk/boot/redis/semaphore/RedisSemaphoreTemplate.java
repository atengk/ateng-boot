package io.github.atengk.boot.redis.semaphore;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 生产级受管分布式信号量执行模板
 * 提供基于 Redisson RSemaphore 的并发度治理、超时快速熔断、降级兜底与确定性释放机制
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class RedisSemaphoreTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisSemaphoreTemplate.class);

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    public RedisSemaphoreTemplate(RedissonClient redissonClient, RedisProperties redisProperties) {
        Assert.notNull(redissonClient, "redissonClient must not be null");
        Assert.notNull(redisProperties, "redisProperties must not be null");
        this.redissonClient = redissonClient;
        this.redisProperties = redisProperties;
    }

    /**
     * 单许可证非阻塞受管闭包（快速失败，无降级）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额（首次调用时原子设置）
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String name, int totalPermits, SemaphoreSupplier<T, E> supplier) throws E {
        return execute(name, totalPermits, 1, Duration.ZERO, supplier, null);
    }

    /**
     * 单许可证带超时等待受管闭包（无降级）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param waitTimeout 最大等待时长
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String name, int totalPermits, Duration waitTimeout,
                                             SemaphoreSupplier<T, E> supplier) throws E {
        return execute(name, totalPermits, 1, waitTimeout, supplier, null);
    }

    /**
     * 单许可证带超时等待与降级回调受管闭包
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param waitTimeout 最大等待时长
     * @param supplier 受保护业务闭包
     * @param fallback 降级兜底闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务结果或降级结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String name, int totalPermits, Duration waitTimeout,
                                             SemaphoreSupplier<T, E> supplier,
                                             SemaphoreSupplier<T, E> fallback) throws E {
        return execute(name, totalPermits, 1, waitTimeout, supplier, fallback);
    }

    /**
     * 多许可证带超时等待受管闭包（无降级）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param acquirePermits 申请许可证数量
     * @param waitTimeout 最大等待时长
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String name, int totalPermits, int acquirePermits,
                                             Duration waitTimeout,
                                             SemaphoreSupplier<T, E> supplier) throws E {
        return execute(name, totalPermits, acquirePermits, waitTimeout, supplier, null);
    }

    /**
     * 全参数带返回值受管信号量执行闭包
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额（首次调用时原子初始化）
     * @param acquirePermits 申请许可证数量
     * @param waitTimeout 最大等待时长（null、0 或负数表示非阻塞快速失败）
     * @param supplier 受保护业务闭包
     * @param fallback 争锁失败降级闭包（为 null 时超时抛出 429 业务异常）
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务计算结果或降级结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String name, int totalPermits, int acquirePermits,
                                             Duration waitTimeout,
                                             SemaphoreSupplier<T, E> supplier,
                                             SemaphoreSupplier<T, E> fallback) throws E {
        Assert.hasText(name, "semaphore name must not be blank");
        Assert.isTrue(totalPermits > 0, "totalPermits must be greater than 0");
        Assert.isTrue(acquirePermits > 0, "acquirePermits must be greater than 0");
        Assert.isTrue(acquirePermits <= totalPermits, "acquirePermits must not exceed totalPermits");
        Assert.notNull(supplier, "supplier must not be null");

        String key = formatSemaphoreKey(name);
        RSemaphore semaphore = redissonClient.getSemaphore(key);
        // 原子尝试初始化总配额（仅在 key 不存在时初始化，已存在则保留当前状态）
        semaphore.trySetPermits(totalPermits);

        boolean acquired = false;
        try {
            if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
                acquired = semaphore.tryAcquire(acquirePermits);
            } else {
                acquired = semaphore.tryAcquire(acquirePermits, waitTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "获取分布式信号量线程被中断: name=" + name, e);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "获取分布式信号量底层异常: name=" + name + ", error=" + e.getMessage(), e);
        }

        if (!acquired) {
            log.warn("[RedisSemaphore] 获取信号量许可证超时: name={}, totalPermits={}, acquirePermits={}, waitTimeout={}",
                    name, totalPermits, acquirePermits, waitTimeout);
            if (fallback != null) {
                return fallback.get();
            }
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                    "当前系统并发过高，获取信号量许可证超时: " + name);
        }

        try {
            return supplier.get();
        } finally {
            try {
                semaphore.release(acquirePermits);
            } catch (Exception e) {
                log.error("[RedisSemaphore] 释放信号量许可证异常: name={}, permits={}", name, acquirePermits, e);
            }
        }
    }

    /**
     * 单许可证非阻塞受管闭包（无返回值，快速失败）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String name, int totalPermits, SemaphoreAction<E> action) throws E {
        execute(name, totalPermits, 1, Duration.ZERO, action, null);
    }

    /**
     * 单许可证带超时等待受管闭包（无返回值）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param waitTimeout 最大等待时长
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String name, int totalPermits, Duration waitTimeout,
                                             SemaphoreAction<E> action) throws E {
        execute(name, totalPermits, 1, waitTimeout, action, null);
    }

    /**
     * 单许可证带超时等待与降级回调受管闭包（无返回值）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param waitTimeout 最大等待时长
     * @param action 受保护业务闭包
     * @param fallback 降级兜底闭包
     * @param <E> 异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String name, int totalPermits, Duration waitTimeout,
                                             SemaphoreAction<E> action,
                                             SemaphoreAction<E> fallback) throws E {
        execute(name, totalPermits, 1, waitTimeout, action, fallback);
    }

    /**
     * 多许可证带超时等待受管闭包（无返回值）
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param acquirePermits 申请许可证数量
     * @param waitTimeout 最大等待时长
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String name, int totalPermits, int acquirePermits,
                                             Duration waitTimeout,
                                             SemaphoreAction<E> action) throws E {
        execute(name, totalPermits, acquirePermits, waitTimeout, action, null);
    }

    /**
     * 全参数无返回值受管信号量执行闭包
     *
     * @param name 信号量名称
     * @param totalPermits 总许可证配额
     * @param acquirePermits 申请许可证数量
     * @param waitTimeout 最大等待时长
     * @param action 受保护业务闭包
     * @param fallback 争锁失败降级闭包
     * @param <E> 异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String name, int totalPermits, int acquirePermits,
                                             Duration waitTimeout,
                                             SemaphoreAction<E> action,
                                             SemaphoreAction<E> fallback) throws E {
        Assert.notNull(action, "action must not be null");
        execute(name, totalPermits, acquirePermits, waitTimeout, () -> {
            action.run();
            return null;
        }, fallback != null ? () -> {
            fallback.run();
            return null;
        } : null);
    }

    // ==================== 配额运维与状态调配方法 ====================

    /**
     * 原子尝试初始化信号量许可证配额（仅在未设置时生效）
     *
     * @param name 信号量名称
     * @param totalPermits 总配额数
     * @return 是否成功设置
     */
    public boolean trySetPermits(String name, int totalPermits) {
        Assert.hasText(name, "semaphore name must not be blank");
        Assert.isTrue(totalPermits > 0, "totalPermits must be greater than 0");
        return getSemaphore(name).trySetPermits(totalPermits);
    }

    /**
     * 为信号量动态增补许可证配额
     *
     * @param name 信号量名称
     * @param permits 增补配额数
     */
    public void addPermits(String name, int permits) {
        Assert.hasText(name, "semaphore name must not be blank");
        Assert.isTrue(permits > 0, "permits must be greater than 0");
        getSemaphore(name).addPermits(permits);
    }

    /**
     * 强行重置信号量许可证总量配额
     *
     * @param name 信号量名称
     * @param totalPermits 新的总配额数
     * @return 是否重置成功
     */
    public boolean resetPermits(String name, int totalPermits) {
        Assert.hasText(name, "semaphore name must not be blank");
        Assert.isTrue(totalPermits > 0, "totalPermits must be greater than 0");
        RSemaphore semaphore = getSemaphore(name);
        semaphore.delete();
        return semaphore.trySetPermits(totalPermits);
    }

    /**
     * 获取指定信号量当前剩余可用许可证数
     *
     * @param name 信号量名称
     * @return 剩余可用许可证数
     */
    public int availablePermits(String name) {
        Assert.hasText(name, "semaphore name must not be blank");
        return getSemaphore(name).availablePermits();
    }

    /**
     * 销毁并删除指定信号量
     *
     * @param name 信号量名称
     * @return 是否删除成功
     */
    public boolean delete(String name) {
        Assert.hasText(name, "semaphore name must not be blank");
        return getSemaphore(name).delete();
    }

    /**
     * 获取底层原生 RSemaphore 实例
     *
     * @param name 信号量名称
     * @return RSemaphore 实例
     */
    public RSemaphore getSemaphore(String name) {
        Assert.hasText(name, "semaphore name must not be blank");
        return redissonClient.getSemaphore(formatSemaphoreKey(name));
    }

    private String formatSemaphoreKey(String name) {
        Assert.hasText(name, "semaphore name must not be blank");
        String prefix = redisProperties.getKeyPrefix();
        if (StringUtils.hasText(prefix)) {
            String trimmedPrefix = prefix.trim();
            if (!trimmedPrefix.endsWith(":")) {
                trimmedPrefix += ":";
            }
            if (!name.startsWith(trimmedPrefix)) {
                return trimmedPrefix + name;
            }
        }
        return name;
    }
}
