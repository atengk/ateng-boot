package io.github.atengk.boot.redis.idempotent;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 生产级通用业务分布式防重幂等执行模板
 * 适用于非 Web 链路（定时调度、MQ 消息消费、异步 RPC 回调等）
 * 内置三态状态机（TRYING -> SUCCESS / FAILED），默认 deleteOnFailure = true 失败自动删除防重 Key 允许上游重试
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class RedisIdempotentTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotentTemplate.class);

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    public RedisIdempotentTemplate(RedissonClient redissonClient, RedisProperties redisProperties) {
        Assert.notNull(redissonClient, "redissonClient must not be null");
        Assert.notNull(redisProperties, "redisProperties must not be null");
        this.redissonClient = redissonClient;
        this.redisProperties = redisProperties;
    }

    /**
     * 最简防重幂等执行闭包（带返回值，默认继承全局 TTL 与 deleteOnFailure=true 配置）
     *
     * @param key 业务幂等键
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 业务受控异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String key, IdempotentSupplier<T, E> supplier) throws E {
        return execute(null, key, null, true, null, supplier);
    }

    /**
     * 带指定 TTL 的防重幂等执行闭包（带返回值，默认 deleteOnFailure=true）
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 业务受控异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String key, Duration ttl, IdempotentSupplier<T, E> supplier) throws E {
        return execute(null, key, ttl, true, null, supplier);
    }

    /**
     * 带 TTL 与失败删除策略的防重幂等执行闭包（带返回值）
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param deleteOnFailure 异常时是否删除 Key
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 业务受控异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String key, Duration ttl, boolean deleteOnFailure,
                                             IdempotentSupplier<T, E> supplier) throws E {
        return execute(null, key, ttl, deleteOnFailure, null, supplier);
    }

    /**
     * 带自定义拦截提示的防重幂等执行闭包（带返回值）
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param deleteOnFailure 异常时是否删除 Key
     * @param message 重复提交提示语
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 业务受控异常类型
     * @return 业务结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String key, Duration ttl, boolean deleteOnFailure,
                                             String message, IdempotentSupplier<T, E> supplier) throws E {
        return execute(null, key, ttl, deleteOnFailure, message, supplier);
    }

    /**
     * 全参数防重幂等执行闭包（带返回值）
     *
     * @param prefix 自定义前缀（可选）
     * @param key 业务幂等键
     * @param ttl 凭据保留时长（若为空或非正则使用默认配置）
     * @param deleteOnFailure 业务异常时是否主动删除防重 Key 以便上游重试
     * @param message 拦截提示消息
     * @param supplier 受保护业务闭包
     * @param <T> 返回值类型
     * @param <E> 业务受控异常类型
     * @return 业务计算结果
     * @throws E 业务受控异常
     */
    public <T, E extends Throwable> T execute(String prefix, String key, Duration ttl,
                                             boolean deleteOnFailure, String message,
                                             IdempotentSupplier<T, E> supplier) throws E {
        Assert.hasText(key, "idempotent key must not be blank");
        Assert.notNull(supplier, "supplier must not be null");

        Duration effectiveTtl = resolveEffectiveTtl(ttl);
        String fullKey = formatKey(prefix, key);
        RBucket<String> bucket = redissonClient.getBucket(fullKey, StringCodec.INSTANCE);

        // 1. 原子抢占 TRYING 状态
        boolean acquired;
        try {
            acquired = bucket.setIfAbsent(IdempotentStatus.TRYING.name(), effectiveTtl);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis 幂等抢占底层异常: key=" + fullKey + ", error=" + e.getMessage(), e);
        }

        if (!acquired) {
            String errorMsg = StringUtils.hasText(message) ? message : CommonResultCode.REPEAT_SUBMIT.getMsg();
            log.warn("[RedisIdempotent] 重复操作已被拦截: key={}", fullKey);
            throw new IdempotentException(errorMsg);
        }

        // 2. 执行受保护业务逻辑，并在状态机中流转
        try {
            T result = supplier.get();
            try {
                bucket.set(IdempotentStatus.SUCCESS.name(), effectiveTtl);
            } catch (Exception e) {
                log.warn("[RedisIdempotent] 更新幂等状态为 SUCCESS 异常: key={}", fullKey, e);
            }
            return result;
        } catch (Throwable ex) {
            if (deleteOnFailure) {
                try {
                    bucket.delete();
                    log.debug("[RedisIdempotent] 业务执行失败，已自动删除幂等 Key: key={}", fullKey);
                } catch (Exception e) {
                    log.warn("[RedisIdempotent] 业务异常后删除幂等 Key 失败: key={}", fullKey, e);
                }
            } else {
                try {
                    bucket.set(IdempotentStatus.FAILED.name(), effectiveTtl);
                    log.debug("[RedisIdempotent] 业务执行失败，已标记幂等状态为 FAILED: key={}", fullKey);
                } catch (Exception e) {
                    log.warn("[RedisIdempotent] 更新幂等状态为 FAILED 异常: key={}", fullKey, e);
                }
            }
            throw ex;
        }
    }

    /**
     * 最简无返回值防重幂等执行闭包
     *
     * @param key 业务幂等键
     * @param action 受保护业务闭包
     * @param <E> 业务受控异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String key, IdempotentAction<E> action) throws E {
        execute(null, key, null, true, null, action);
    }

    /**
     * 带指定 TTL 的无返回值防重幂等执行闭包
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param action 受保护业务闭包
     * @param <E> 业务受控异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String key, Duration ttl, IdempotentAction<E> action) throws E {
        execute(null, key, ttl, true, null, action);
    }

    /**
     * 带 TTL 与失败删除策略的无返回值防重幂等执行闭包
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param deleteOnFailure 异常时是否删除 Key
     * @param action 受保护业务闭包
     * @param <E> 业务受控异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String key, Duration ttl, boolean deleteOnFailure,
                                             IdempotentAction<E> action) throws E {
        execute(null, key, ttl, deleteOnFailure, null, action);
    }

    /**
     * 带自定义拦截提示的无返回值防重幂等执行闭包
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param deleteOnFailure 异常时是否删除 Key
     * @param message 重复提交提示语
     * @param action 受保护业务闭包
     * @param <E> 业务受控异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String key, Duration ttl, boolean deleteOnFailure,
                                             String message, IdempotentAction<E> action) throws E {
        execute(null, key, ttl, deleteOnFailure, message, action);
    }

    /**
     * 全参数无返回值防重幂等执行闭包
     *
     * @param prefix 自定义前缀（可选）
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @param deleteOnFailure 业务异常时是否主动删除防重 Key 以便重试
     * @param message 拦截提示消息
     * @param action 受保护业务闭包
     * @param <E> 业务受控异常类型
     * @throws E 业务受控异常
     */
    public <E extends Throwable> void execute(String prefix, String key, Duration ttl,
                                             boolean deleteOnFailure, String message,
                                             IdempotentAction<E> action) throws E {
        Assert.notNull(action, "action must not be null");
        execute(prefix, key, ttl, deleteOnFailure, message, () -> {
            action.run();
            return null;
        });
    }

    // ==========================================
    // 对称运维与状态查询操作方法 (Symmetric Facades)
    // ==========================================

    /**
     * 尝试原子抢占幂等占位（非阻塞抢占 TRYING 状态）
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @return 是否成功抢占
     */
    public boolean tryAcquire(String key, Duration ttl) {
        return tryAcquire(null, key, ttl);
    }

    /**
     * 带自定义前缀的原子抢占幂等占位
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     * @return 是否成功抢占
     */
    public boolean tryAcquire(String prefix, String key, Duration ttl) {
        Assert.hasText(key, "idempotent key must not be blank");
        Duration effectiveTtl = resolveEffectiveTtl(ttl);
        return getBucket(prefix, key).setIfAbsent(IdempotentStatus.TRYING.name(), effectiveTtl);
    }

    /**
     * 显式将幂等状态推进为 SUCCESS 终态
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     */
    public void setSuccess(String key, Duration ttl) {
        setSuccess(null, key, ttl);
    }

    /**
     * 带自定义前缀显式将幂等状态推进为 SUCCESS 终态
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     */
    public void setSuccess(String prefix, String key, Duration ttl) {
        Assert.hasText(key, "idempotent key must not be blank");
        Duration effectiveTtl = resolveEffectiveTtl(ttl);
        getBucket(prefix, key).set(IdempotentStatus.SUCCESS.name(), effectiveTtl);
    }

    /**
     * 显式将幂等状态推进为 FAILED 终态
     *
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     */
    public void setFailed(String key, Duration ttl) {
        setFailed(null, key, ttl);
    }

    /**
     * 带自定义前缀显式将幂等状态推进为 FAILED 终态
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @param ttl 凭据保留时长
     */
    public void setFailed(String prefix, String key, Duration ttl) {
        Assert.hasText(key, "idempotent key must not be blank");
        Duration effectiveTtl = resolveEffectiveTtl(ttl);
        getBucket(prefix, key).set(IdempotentStatus.FAILED.name(), effectiveTtl);
    }

    /**
     * 删除指定幂等 Key
     *
     * @param key 业务幂等键
     * @return 是否成功删除
     */
    public boolean delete(String key) {
        return delete(null, key);
    }

    /**
     * 带自定义前缀删除指定幂等 Key
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @return 是否成功删除
     */
    public boolean delete(String prefix, String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        return getBucket(prefix, key).delete();
    }

    /**
     * 获取指定幂等 Key 当前状态
     *
     * @param key 业务幂等键
     * @return 当前状态枚举，不存在时返回 null
     */
    public IdempotentStatus getStatus(String key) {
        return getStatus(null, key);
    }

    /**
     * 带自定义前缀获取指定幂等 Key 当前状态
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @return 当前状态枚举，不存在时返回 null
     */
    public IdempotentStatus getStatus(String prefix, String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        String value = getBucket(prefix, key).get();
        return IdempotentStatus.fromString(value);
    }

    /**
     * 判断指定幂等 Key 是否已成功执行完毕 (SUCCESS)
     *
     * @param key 业务幂等键
     * @return 是否已成功
     */
    public boolean isSuccess(String key) {
        return IdempotentStatus.SUCCESS == getStatus(key);
    }

    /**
     * 判断指定幂等 Key 是否正在执行中 (TRYING)
     *
     * @param key 业务幂等键
     * @return 是否正在执行
     */
    public boolean isTrying(String key) {
        return IdempotentStatus.TRYING == getStatus(key);
    }

    /**
     * 判断指定幂等 Key 是否已失败 (FAILED)
     *
     * @param key 业务幂等键
     * @return 是否已失败
     */
    public boolean isFailed(String key) {
        return IdempotentStatus.FAILED == getStatus(key);
    }

    /**
     * 获取底层原生 RBucket 实例
     *
     * @param key 业务幂等键
     * @return 原生 RBucket
     */
    public RBucket<String> getBucket(String key) {
        return getBucket(null, key);
    }

    /**
     * 带自定义前缀获取底层原生 RBucket 实例
     *
     * @param prefix 自定义前缀
     * @param key 业务幂等键
     * @return 原生 RBucket
     */
    public RBucket<String> getBucket(String prefix, String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        String fullKey = formatKey(prefix, key);
        return redissonClient.getBucket(fullKey, StringCodec.INSTANCE);
    }

    /**
     * 格式化生成完整 Redis 幂等 Key
     *
     * @param key 业务幂等键
     * @return 完整 Redis Key
     */
    public String formatKey(String key) {
        return formatKey(null, key);
    }

    /**
     * 格式化生成完整 Redis 幂等 Key（支持自定义业务前缀与全局前缀叠加）
     *
     * @param customPrefix 自定义业务前缀（可选）
     * @param key 业务幂等键
     * @return 完整 Redis Key
     */
    public String formatKey(String customPrefix, String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        String prefix = customPrefix != null
                ? customPrefix
                : (redisProperties.getIdempotent() != null && redisProperties.getIdempotent().getPrefix() != null
                        ? redisProperties.getIdempotent().getPrefix()
                        : "idempotent:");

        String globalPrefix = redisProperties.getKeyPrefix();
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(globalPrefix)) {
            String trimmedGlobal = globalPrefix.trim();
            sb.append(trimmedGlobal);
            if (!trimmedGlobal.endsWith(":")) {
                sb.append(":");
            }
        }
        if (StringUtils.hasText(prefix)) {
            String trimmedPrefix = prefix.trim();
            sb.append(trimmedPrefix);
            if (!trimmedPrefix.endsWith(":") && !key.startsWith(":")) {
                sb.append(":");
            }
        }
        sb.append(key.trim());
        return sb.toString();
    }

    private Duration resolveEffectiveTtl(Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            return ttl;
        }
        if (redisProperties.getIdempotent() != null && redisProperties.getIdempotent().getDefaultExpire() != null) {
            return redisProperties.getIdempotent().getDefaultExpire();
        }
        return Duration.ofMinutes(10);
    }
}
