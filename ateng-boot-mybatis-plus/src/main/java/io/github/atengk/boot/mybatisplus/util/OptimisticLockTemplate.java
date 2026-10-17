package io.github.atengk.boot.mybatisplus.util;

import io.github.atengk.boot.core.util.RetryUtil;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 乐观锁并发自旋重试模板门面
 * 依托 core 模块纯原生 {@link RetryUtil} 实现，针对 MyBatis-Plus 乐观锁 (Version) 并发冲突（更新行数 = 0）
 * 提供自动重新加载最新版本、自旋重试与指数退避守护，杜绝业务层手工手写 while 循环与死锁风险
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class OptimisticLockTemplate {

    /**
     * 默认最大重试次数（包含首次执行，共 3 次）
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 默认初始退避等待时长（50ms）
     */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(50);

    /**
     * 默认指数退避乘数（1.5 倍）
     */
    public static final double DEFAULT_MULTIPLIER = 1.5;

    private OptimisticLockTemplate() {
    }

    /**
     * 执行带乐观锁自旋重试的实体加载与更新操作（采用默认重试配置）
     * 每次循环先调用 loader 加载最新实体，修改后执行 updater 更新；若 updater 返回 false（更新失败，版本冲突），则自动退避重试
     *
     * @param loader  实体加载器（如 () -> mapper.selectById(id)）
     * @param updater 实体更新断言器（入参为 loader 获取的最新实体，执行修改与持久层 update，成功返回 true，版本冲突返回 false）
     * @param <T>     实体类型
     * @return 更新成功后的实体对象
     * @throws OptimisticLockConflictException 当重试达到上限仍未成功时抛出
     */
    public static <T> T execute(Supplier<T> loader, Predicate<T> updater) {
        return execute(loader, updater, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY);
    }

    /**
     * 执行带乐观锁自旋重试的实体加载与更新操作（支持自定义重试次数与退避时长）
     *
     * @param loader      实体加载器
     * @param updater     实体更新断言器
     * @param maxAttempts 最大尝试次数（大于等于 1）
     * @param delay       初始退避等待时长
     * @param <T>         实体类型
     * @return 更新成功后的实体对象
     * @throws OptimisticLockConflictException 当重试达到上限仍未成功时抛出
     */
    public static <T> T execute(Supplier<T> loader, Predicate<T> updater, int maxAttempts, Duration delay) {
        RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
                .maxAttempts(Math.max(1, maxAttempts))
                .delay(delay != null ? delay : DEFAULT_INITIAL_DELAY)
                .multiplier(DEFAULT_MULTIPLIER)
                .retryPredicate(ex -> ex instanceof OptimisticLockConflictException)
                .build();
        return execute(loader, updater, config);
    }

    /**
     * 执行带乐观锁自旋重试的实体加载与更新操作（全功能定制 RetryConfig）
     *
     * @param loader      实体加载器
     * @param updater     实体更新断言器
     * @param retryConfig 核心重试配置策略
     * @param <T>         实体类型
     * @return 更新成功后的实体对象
     * @throws OptimisticLockConflictException 当重试达到上限仍未成功时抛出
     */
    public static <T> T execute(Supplier<T> loader, Predicate<T> updater, RetryUtil.RetryConfig retryConfig) {
        Objects.requireNonNull(loader, "Entity loader supplier must not be null");
        Objects.requireNonNull(updater, "Entity updater predicate must not be null");
        Objects.requireNonNull(retryConfig, "RetryConfig must not be null");

        try {
            return RetryUtil.execute(() -> {
                T entity = loader.get();
                if (entity == null) {
                    throw new IllegalArgumentException("OptimisticLock loader returned null entity");
                }
                boolean success = updater.test(entity);
                if (!success) {
                    throw new OptimisticLockConflictException("Optimistic lock conflict detected: update affected 0 rows");
                }
                return entity;
            }, retryConfig);
        } catch (OptimisticLockConflictException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Optimistic lock execution failed", ex);
        }
    }

    /**
     * 乐观锁版本并发冲突异常
     */
    public static class OptimisticLockConflictException extends RuntimeException {
        public OptimisticLockConflictException(String message) {
            super(message);
        }
    }
}
