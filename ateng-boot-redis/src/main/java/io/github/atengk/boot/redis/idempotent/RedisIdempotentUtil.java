package io.github.atengk.boot.redis.idempotent;

import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * 分布式防重幂等全局静态操作门面工具类
 * 静态委托给受管单例 RedisIdempotentTemplate，提供无侵入快速调用范式
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class RedisIdempotentUtil {

    private static volatile RedisIdempotentTemplate staticTemplate;

    private RedisIdempotentUtil() {
    }

    /**
     * 容器初始化绑定受管模板实例
     *
     * @param template 受管幂等模板
     */
    public static void init(RedisIdempotentTemplate template) {
        Assert.notNull(template, "RedisIdempotentTemplate must not be null");
        staticTemplate = template;
    }

    /**
     * 最简防重幂等执行闭包（带返回值）
     */
    public static <T, E extends Throwable> T execute(String key, IdempotentSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, supplier);
    }

    /**
     * 带指定 TTL 的防重幂等执行闭包（带返回值）
     */
    public static <T, E extends Throwable> T execute(String key, Duration ttl, IdempotentSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, ttl, supplier);
    }

    /**
     * 带 TTL 与失败删除策略的防重幂等执行闭包（带返回值）
     */
    public static <T, E extends Throwable> T execute(String key, Duration ttl, boolean deleteOnFailure,
                                                     IdempotentSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, ttl, deleteOnFailure, supplier);
    }

    /**
     * 带自定义拦截提示的防重幂等执行闭包（带返回值）
     */
    public static <T, E extends Throwable> T execute(String key, Duration ttl, boolean deleteOnFailure,
                                                     String message, IdempotentSupplier<T, E> supplier) throws E {
        return getTemplate().execute(key, ttl, deleteOnFailure, message, supplier);
    }

    /**
     * 全参数防重幂等执行闭包（带返回值）
     */
    public static <T, E extends Throwable> T execute(String prefix, String key, Duration ttl,
                                                     boolean deleteOnFailure, String message,
                                                     IdempotentSupplier<T, E> supplier) throws E {
        return getTemplate().execute(prefix, key, ttl, deleteOnFailure, message, supplier);
    }

    /**
     * 最简无返回值防重幂等执行闭包
     */
    public static <E extends Throwable> void execute(String key, IdempotentAction<E> action) throws E {
        getTemplate().execute(key, action);
    }

    /**
     * 带指定 TTL 的无返回值防重幂等执行闭包
     */
    public static <E extends Throwable> void execute(String key, Duration ttl, IdempotentAction<E> action) throws E {
        getTemplate().execute(key, ttl, action);
    }

    /**
     * 带 TTL 与失败删除策略的无返回值防重幂等执行闭包
     */
    public static <E extends Throwable> void execute(String key, Duration ttl, boolean deleteOnFailure,
                                                     IdempotentAction<E> action) throws E {
        getTemplate().execute(key, ttl, deleteOnFailure, action);
    }

    /**
     * 带自定义拦截提示的无返回值防重幂等执行闭包
     */
    public static <E extends Throwable> void execute(String key, Duration ttl, boolean deleteOnFailure,
                                                     String message, IdempotentAction<E> action) throws E {
        getTemplate().execute(key, ttl, deleteOnFailure, message, action);
    }

    /**
     * 全参数无返回值防重幂等执行闭包
     */
    public static <E extends Throwable> void execute(String prefix, String key, Duration ttl,
                                                     boolean deleteOnFailure, String message,
                                                     IdempotentAction<E> action) throws E {
        getTemplate().execute(prefix, key, ttl, deleteOnFailure, message, action);
    }

    /**
     * 尝试原子抢占幂等占位
     */
    public static boolean tryAcquire(String key, Duration ttl) {
        return getTemplate().tryAcquire(key, ttl);
    }

    /**
     * 显式将幂等状态推进为 SUCCESS 终态
     */
    public static void setSuccess(String key, Duration ttl) {
        getTemplate().setSuccess(key, ttl);
    }

    /**
     * 显式将幂等状态推进为 FAILED 终态
     */
    public static void setFailed(String key, Duration ttl) {
        getTemplate().setFailed(key, ttl);
    }

    /**
     * 删除指定幂等 Key
     */
    public static boolean delete(String key) {
        return getTemplate().delete(key);
    }

    /**
     * 获取指定幂等 Key 当前状态
     */
    public static IdempotentStatus getStatus(String key) {
        return getTemplate().getStatus(key);
    }

    /**
     * 判断指定幂等 Key 是否已成功执行完毕 (SUCCESS)
     */
    public static boolean isSuccess(String key) {
        return getTemplate().isSuccess(key);
    }

    /**
     * 判断指定幂等 Key 是否正在执行中 (TRYING)
     */
    public static boolean isTrying(String key) {
        return getTemplate().isTrying(key);
    }

    /**
     * 判断指定幂等 Key 是否已失败 (FAILED)
     */
    public static boolean isFailed(String key) {
        return getTemplate().isFailed(key);
    }

    /**
     * 格式化生成完整 Redis 幂等 Key
     */
    public static String formatKey(String key) {
        return getTemplate().formatKey(key);
    }

    /**
     * 获取受管模板实例
     */
    public static RedisIdempotentTemplate getTemplate() {
        Assert.state(staticTemplate != null,
                "RedisIdempotentTemplate has not been initialized. Ensure Spring context has loaded.");
        return staticTemplate;
    }
}
