package io.github.atengk.boot.flyway.spi;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 分布式迁移并发互斥锁扩展契约（SPI）
 * <p>
 * 多副本并发启动或运行期动态挂载新租户库执行迁移时提供目标级互斥锁定，
 * 具备 {@link #tryLock(String, Duration)} 与 {@link #unlock(String)} 对称契约；
 * 缺省实现委托 Flyway 原生数据库锁及连接重试参数保障集群并发安全。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface FlywayLockProvider {

    /**
     * 尝试申请指定目标数据源的迁移互斥锁
     *
     * @param targetKey   目标数据源标识
     * @param waitTimeout 最大等待超时时间
     * @return true 表示成功持锁，false 表示在超时窗口内未获取到锁
     */
    boolean tryLock(String targetKey, Duration waitTimeout);

    /**
     * 释放指定目标数据源的迁移互斥锁（由 {@code finally} 块保证对称调用）
     *
     * @param targetKey 目标数据源标识
     */
    void unlock(String targetKey);

    /**
     * 在受锁保护临界区内执行迁移/修复等写操作并在结束时自动释锁
     *
     * @param targetKey   目标数据源标识
     * @param waitTimeout 最大等待超时时间
     * @param action      受保护执行体
     * @param <T>         返回值类型
     * @return 执行体返回值
     */
    default <T> T executeWithLock(String targetKey, Duration waitTimeout, Supplier<T> action) {
        Objects.requireNonNull(action, "action 不能为空");
        Duration effectiveTimeout = waitTimeout != null ? waitTimeout : Duration.ofSeconds(30);
        boolean locked = tryLock(targetKey, effectiveTimeout);
        if (!locked) {
            throw new FlywayException(FlywayResultCode.LOCK_TIMEOUT,
                    "获取迁移互斥锁超时 [target=" + targetKey + ", timeout=" + effectiveTimeout + "]");
        }
        Throwable primaryError = null;
        try {
            return action.get();
        } catch (Throwable ex) {
            primaryError = ex;
            throw ex;
        } finally {
            try {
                unlock(targetKey);
            } catch (Exception unlockEx) {
                if (primaryError != null) {
                    primaryError.addSuppressed(unlockEx);
                } else {
                    throw new FlywayException(FlywayResultCode.LOCK_TIMEOUT,
                            "释放迁移互斥锁异常 [target=" + targetKey + "]: "
                                    + FlywayConfigurationSupport.sanitizeSensitiveText(unlockEx.getMessage()),
                            unlockEx);
                }
            }
        }
    }
}
