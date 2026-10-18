package io.github.atengk.boot.redis.lock.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生产级编程式分布式锁执行模板
 * 提供函数式闭包受管执行，自动收敛全局前缀隔离、四阶锁模式、看门狗自动延期、安全持锁释放与慢锁审计
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DistributedLockTemplate {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockTemplate.class);

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    public DistributedLockTemplate(RedissonClient redissonClient, RedisProperties redisProperties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "RedissonClient must not be null");
        this.redisProperties = Objects.requireNonNull(redisProperties, "RedisProperties must not be null");
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
    public <T, E extends Throwable> T execute(String key, LockSupplier<T, E> supplier) throws E {
        return execute(Collections.singletonList(key), LockMode.REENTRANT, -1L, -1L, null, null, supplier, null);
    }

    /**
     * 最简加锁执行闭包（无返回值，默认可重入排他锁，继承全局超时与看门狗配置）
     *
     * @param key 业务锁键
     * @param action 受保护业务闭包
     * @param <E> 异常类型
     * @throws E 业务抛出的异常
     */
    public <E extends Throwable> void execute(String key, LockAction<E> action) throws E {
        execute(Collections.singletonList(key), LockMode.REENTRANT, -1L, -1L, null, null, () -> {
            action.run();
            return null;
        }, null);
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
    public <T, E extends Throwable> T execute(String key, LockSupplier<T, E> supplier, LockSupplier<T, E> fallback) throws E {
        return execute(Collections.singletonList(key), LockMode.REENTRANT, -1L, -1L, null, null, supplier, fallback);
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
    public <E extends Throwable> void execute(String key, LockAction<E> action, LockAction<E> fallback) throws E {
        execute(Collections.singletonList(key), LockMode.REENTRANT, -1L, -1L, null, null, () -> {
            action.run();
            return null;
        }, fallback != null ? () -> {
            fallback.run();
            return null;
        } : null);
    }

    /**
     * 单键自定义超时与锁模式加锁执行（带返回值）
     */
    public <T, E extends Throwable> T execute(String key, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockSupplier<T, E> supplier) throws E {
        return execute(Collections.singletonList(key), mode, waitTime, leaseTime, timeUnit, null, supplier, null);
    }

    /**
     * 单键自定义超时与锁模式加锁执行（无返回值）
     */
    public <E extends Throwable> void execute(String key, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        execute(Collections.singletonList(key), mode, waitTime, leaseTime, timeUnit, null, () -> {
            action.run();
            return null;
        }, null);
    }

    /**
     * 多键联锁加锁执行（带返回值）
     */
    public <T, E extends Throwable> T execute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockSupplier<T, E> supplier) throws E {
        return execute(keys, mode, waitTime, leaseTime, timeUnit, null, supplier, null);
    }

    /**
     * 多键联锁加锁执行（无返回值）
     */
    public <E extends Throwable> void execute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        execute(keys, mode, waitTime, leaseTime, timeUnit, null, () -> {
            action.run();
            return null;
        }, null);
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
    public <E extends Throwable> boolean tryExecute(String key, LockAction<E> action) throws E {
        return tryExecute(Collections.singletonList(key), LockMode.REENTRANT, 0L, -1L, null, action);
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
    public <E extends Throwable> boolean tryExecute(String key, long waitTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        return tryExecute(Collections.singletonList(key), LockMode.REENTRANT, waitTime, -1L, timeUnit, action);
    }

    /**
     * 多键联锁争锁试探执行
     */
    public <E extends Throwable> boolean tryExecute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, LockAction<E> action) throws E {
        AtomicBoolean executed = new AtomicBoolean(false);
        execute(keys, mode, waitTime, leaseTime, timeUnit, null, () -> {
            action.run();
            executed.set(true);
            return null;
        }, () -> null);
        return executed.get();
    }

    /**
     * 全能分布式锁受管执行核心方法
     *
     * @param keys 锁键列表
     * @param mode 锁模式（REENTRANT, FAIR, READ, WRITE）
     * @param waitTime 最大等待获取锁时长（<0 使用全局默认配置）
     * @param leaseTime 持锁租约时长（<=0 激活看门狗自动延期）
     * @param timeUnit 时间单位（null 使用全局默认配置）
     * @param message 自定义争锁超时提示文案
     * @param supplier 业务执行闭包
     * @param fallback 争锁失败降级闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务计算结果或降级结果
     * @throws E 业务抛出的异常
     */
    public <T, E extends Throwable> T execute(List<String> keys, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, String message, LockSupplier<T, E> supplier, LockSupplier<T, E> fallback) throws E {
        return execute(keys, null, mode, waitTime, leaseTime, timeUnit, message, supplier, fallback);
    }

    /**
     * 全能分布式锁受管执行核心方法（支持自定义键名前缀）
     *
     * @param keys 锁键列表
     * @param prefix 自定义锁前缀（null 时采用配置的全局锁前缀）
     * @param mode 锁模式（REENTRANT, FAIR, READ, WRITE）
     * @param waitTime 最大等待获取锁时长（<0 使用全局默认配置）
     * @param leaseTime 持锁租约时长（<=0 激活看门狗自动延期）
     * @param timeUnit 时间单位（null 使用全局默认配置）
     * @param message 自定义争锁超时提示文案
     * @param supplier 业务执行闭包
     * @param fallback 争锁失败降级闭包
     * @param <T> 返回值类型
     * @param <E> 异常类型
     * @return 业务计算结果或降级结果
     * @throws E 业务抛出的异常
     */
    public <T, E extends Throwable> T execute(List<String> keys, String prefix, LockMode mode, long waitTime, long leaseTime, TimeUnit timeUnit, String message, LockSupplier<T, E> supplier, LockSupplier<T, E> fallback) throws E {
        Objects.requireNonNull(keys, "keys must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");

        LockMode actualMode = mode != null ? mode : LockMode.REENTRANT;
        TimeUnit actualTimeUnit = timeUnit != null ? timeUnit : (redisProperties.getLock().getTimeUnit() != null ? redisProperties.getLock().getTimeUnit() : TimeUnit.SECONDS);

        long actualWaitTime;
        if (waitTime >= 0) {
            actualWaitTime = waitTime;
        } else {
            Duration configWait = redisProperties.getLock().getWaitTime();
            if (configWait != null) {
                long converted = actualTimeUnit.convert(configWait.toMillis(), TimeUnit.MILLISECONDS);
                if (converted == 0 && !configWait.isZero()) {
                    actualTimeUnit = TimeUnit.MILLISECONDS;
                    actualWaitTime = configWait.toMillis();
                } else {
                    actualWaitTime = converted;
                }
            } else {
                actualWaitTime = actualTimeUnit.convert(3, TimeUnit.SECONDS);
            }
        }

        long actualLeaseTime;
        if (leaseTime >= 0) {
            actualLeaseTime = leaseTime;
        } else {
            Duration configLease = redisProperties.getLock().getLeaseTime();
            if (configLease == null || configLease.isNegative() || configLease.isZero()) {
                actualLeaseTime = -1L;
            } else {
                long converted = actualTimeUnit.convert(configLease.toMillis(), TimeUnit.MILLISECONDS);
                if (converted == 0 && !configLease.isZero()) {
                    actualLeaseTime = configLease.toMillis();
                    actualWaitTime = TimeUnit.MILLISECONDS.convert(actualWaitTime, actualTimeUnit);
                    actualTimeUnit = TimeUnit.MILLISECONDS;
                } else {
                    actualLeaseTime = converted;
                }
            }
        }

        List<String> resolvedKeys = resolveKeys(keys, prefix);
        RLock rLock = createRLock(resolvedKeys, actualMode);

        long acquireStart = System.currentTimeMillis();
        boolean acquired;
        try {
            if (actualLeaseTime > 0) {
                acquired = rLock.tryLock(actualWaitTime, actualLeaseTime, actualTimeUnit);
            } else {
                // leaseTime <= 0 激活 Redisson 看门狗 Watchdog 自动续期
                acquired = rLock.tryLock(actualWaitTime, actualTimeUnit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DistributedLock] 获取分布式锁被线程中断: keys={}", resolvedKeys);
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "加锁线程已被中断", e);
        }

        long acquireCost = System.currentTimeMillis() - acquireStart;
        long slowThreshold = redisProperties.getLock().getSlowThresholdMs();
        if (slowThreshold > 0 && acquireCost >= slowThreshold) {
            log.warn("[DistributedLock] 检测到慢分布式锁争锁: keys={}, mode={}, acquireCost={}ms, slowThreshold={}ms",
                    resolvedKeys, actualMode, acquireCost, slowThreshold);
        }

        if (!acquired) {
            if (fallback != null) {
                log.debug("[DistributedLock] 获取分布式锁超时，执行降级逻辑: keys={}", resolvedKeys);
                return fallback.get();
            }
            String failMessage = StringUtils.hasText(message)
                    ? message
                    : "系统繁忙，获取分布式锁超时，请稍后再试";
            log.warn("[DistributedLock] 获取分布式锁超时，加锁失败: keys={}, mode={}, waitTime={}{}",
                    resolvedKeys, actualMode, actualWaitTime, actualTimeUnit);
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), failMessage);
        }

        log.debug("[DistributedLock] 加锁成功: keys={}, mode={}", resolvedKeys, actualMode);
        long startTime = System.currentTimeMillis();
        try {
            return supplier.get();
        } finally {
            safeUnlockAndAudit(rLock, resolvedKeys, actualMode, startTime);
        }
    }

    /**
     * 安全持锁释放与慢锁耗时审计
     */
    private void safeUnlockAndAudit(RLock rLock, List<String> keys, LockMode mode, long startTime) {
        long cost = System.currentTimeMillis() - startTime;
        long slowThreshold = redisProperties.getLock().getSlowThresholdMs();
        if (slowThreshold > 0 && cost >= slowThreshold) {
            log.warn("[DistributedLock] 检测到慢分布式锁持锁: keys={}, mode={}, holdCost={}ms, slowThreshold={}ms",
                    keys, mode, cost, slowThreshold);
        }

        try {
            boolean shouldUnlock = false;
            try {
                shouldUnlock = rLock.isHeldByCurrentThread();
            } catch (Exception e) {
                // 部分联合锁在异常状态下检查可能报错，降级尝试释放
                shouldUnlock = true;
            }

            if (shouldUnlock) {
                rLock.unlock();
                log.debug("[DistributedLock] 释放分布式锁成功: keys={}", keys);
            } else if (rLock instanceof org.redisson.RedissonMultiLock) {
                // MultiLock 子锁部分过期可能导致 isHeldByCurrentThread 为 false，防御性释放未过期的子锁
                try {
                    rLock.unlock();
                    log.debug("[DistributedLock] MultiLock 级联防御释放成功: keys={}", keys);
                } catch (Exception ignored) {
                }
            } else {
                log.warn("[DistributedLock] 当前线程未持有锁，跳过释放（可能锁已超时或由其他线程持有）: keys={}", keys);
            }
        } catch (Exception e) {
            log.warn("[DistributedLock] 释放分布式锁异常: keys={}", keys, e);
        }
    }

    /**
     * 根据锁模式与键列表创建对应的 RLock 实例
     */
    private RLock createRLock(List<String> keys, LockMode mode) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Distributed lock keys must not be empty");
        }
        LockMode lockMode = mode != null ? mode : LockMode.REENTRANT;

        if (keys.size() == 1) {
            String key = keys.get(0);
            return switch (lockMode) {
                case FAIR -> redissonClient.getFairLock(key);
                case READ -> redissonClient.getReadWriteLock(key).readLock();
                case WRITE -> redissonClient.getReadWriteLock(key).writeLock();
                case REENTRANT -> redissonClient.getLock(key);
            };
        }

        // 多键联锁 MultiLock
        RLock[] lockArray = new RLock[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            lockArray[i] = switch (lockMode) {
                case FAIR -> redissonClient.getFairLock(key);
                case READ -> redissonClient.getReadWriteLock(key).readLock();
                case WRITE -> redissonClient.getReadWriteLock(key).writeLock();
                case REENTRANT -> redissonClient.getLock(key);
            };
        }
        return redissonClient.getMultiLock(lockArray);
    }

    /**
     * 补全全局与锁模块统一前缀
     */
    private List<String> resolveKeys(List<String> keys, String customPrefix) {
        String globalPrefix = redisProperties.getKeyPrefix();
        String lockPrefix = StringUtils.hasText(customPrefix)
                ? customPrefix
                : redisProperties.getLock().getPrefix();
        if (lockPrefix == null) {
            lockPrefix = "lock:";
        }

        List<String> resolved = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String resolvedKey;
            if (key.startsWith(lockPrefix) || (StringUtils.hasText(globalPrefix) && key.startsWith(globalPrefix))) {
                resolvedKey = key;
            } else {
                StringBuilder sb = new StringBuilder();
                if (StringUtils.hasText(globalPrefix)) {
                    sb.append(globalPrefix);
                }
                sb.append(lockPrefix).append(key);
                resolvedKey = sb.toString();
            }
            if (!resolved.contains(resolvedKey)) {
                resolved.add(resolvedKey);
            }
        }
        return resolved;
    }
}
