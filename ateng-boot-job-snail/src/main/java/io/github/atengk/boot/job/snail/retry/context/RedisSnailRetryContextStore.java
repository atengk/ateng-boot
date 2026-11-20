package io.github.atengk.boot.job.snail.retry.context;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link StringRedisTemplate} 的 Fail-Open 分布式重试四维上下文快照存储实现
 *
 * <p>将四维现场编码为 {@link RetryContextSnapshotPayload} 写入 Redis（固定前缀
 * {@link #KEY_PREFIX}，绑定 {@code ateng.boot.job.snail.retry.context-ttl}），
 * 读取时借助 Core 官方公开原语安全重建运行时 {@link AsyncContext.ContextSnapshot}；
 * 当发生网络超时、序列化或命令异常时记录 Warn 告警并 Fail-Open 降级读写内置有界内存存储，
 * 绝不因存储层抖动阻断业务重试主链路。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public class RedisSnailRetryContextStore implements SnailRetryContextStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSnailRetryContextStore.class);

    public static final String KEY_PREFIX = "ateng:boot:job:snail:retry:ctx:";
    private static final String TRACE_ID_KEY = "traceId";
    private static final int CLEAR_SCAN_BATCH_SIZE = 500;

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final InMemorySnailRetryContextStore fallbackStore;

    public RedisSnailRetryContextStore(StringRedisTemplate redisTemplate, SnailJobProperties properties) {
        this(
                redisTemplate,
                properties != null && properties.getRetry() != null ? properties.getRetry().getContextTtl() : null,
                properties != null && properties.getRetry() != null
                        ? properties.getRetry().getInMemoryMaxSize()
                        : InMemorySnailRetryContextStore.DEFAULT_MAX_ENTRIES
        );
    }

    public RedisSnailRetryContextStore(StringRedisTemplate redisTemplate, Duration ttl, int fallbackMaxEntries) {
        this.redisTemplate = redisTemplate;
        this.ttl = (ttl != null && !ttl.isZero() && !ttl.isNegative()) ? ttl : InMemorySnailRetryContextStore.DEFAULT_TTL;
        this.fallbackStore = new InMemorySnailRetryContextStore(fallbackMaxEntries, this.ttl);
    }

    @Override
    public void put(String key, AsyncContext.ContextSnapshot snapshot) {
        if (!StringUtils.hasText(key) || snapshot == null) {
            return;
        }
        String redisKey = buildRedisKey(key);
        if (redisTemplate == null) {
            fallbackStore.put(key, snapshot);
            return;
        }
        try {
            RetryContextSnapshotPayload payload = toPayload(snapshot);
            String json = JsonUtil.toJsonString(payload);
            redisTemplate.opsForValue().set(redisKey, json, ttl);
        } catch (Throwable ex) {
            log.warn("写入 Redis 重试上下文快照失败，Fail-Open 降级写入本地有界内存存储 [Key: {}]: {}",
                    redisKey, ex.getMessage());
            fallbackStore.put(key, snapshot);
        }
    }

    @Override
    public AsyncContext.ContextSnapshot get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String redisKey = buildRedisKey(key);
        if (redisTemplate == null) {
            return fallbackStore.get(key);
        }
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (!StringUtils.hasText(json)) {
                return fallbackStore.get(key);
            }
            RetryContextSnapshotPayload payload = JsonUtil.parseObject(json, RetryContextSnapshotPayload.class);
            if (payload == null) {
                return fallbackStore.get(key);
            }
            return fromPayload(payload);
        } catch (Throwable ex) {
            log.warn("读取或反序列化 Redis 重试上下文快照失败，Fail-Open 降级读取本地有界内存存储 [Key: {}]: {}",
                    redisKey, ex.getMessage());
            return fallbackStore.get(key);
        }
    }

    @Override
    public void remove(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        fallbackStore.remove(key);
        if (redisTemplate == null) {
            return;
        }
        String redisKey = buildRedisKey(key);
        try {
            redisTemplate.delete(redisKey);
        } catch (Throwable ex) {
            log.warn("清理 Redis 重试上下文快照失败，已忽略 [Key: {}]: {}", redisKey, ex.getMessage());
        }
    }

    @Override
    public void clear() {
        fallbackStore.clear();
        if (redisTemplate == null) {
            return;
        }
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(CLEAR_SCAN_BATCH_SIZE)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            if (cursor == null) {
                return;
            }
            List<String> batch = new ArrayList<>(CLEAR_SCAN_BATCH_SIZE);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= CLEAR_SCAN_BATCH_SIZE) {
                    redisTemplate.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        } catch (Throwable ex) {
            log.warn("批量扫描清理 Redis 重试上下文快照失败，已忽略 [Prefix: {}]: {}", KEY_PREFIX, ex.getMessage());
        }
    }

    /**
     * 构建带固定模块前缀的 Redis 存储键
     *
     * @param key 原始上下文快照键
     * @return 完整 Redis 键名
     */
    public String buildRedisKey(String key) {
        String trimmed = key.trim();
        return trimmed.startsWith(KEY_PREFIX) ? trimmed : (KEY_PREFIX + trimmed);
    }

    private RetryContextSnapshotPayload toPayload(AsyncContext.ContextSnapshot snapshot) {
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            snapshot.apply();
            Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
            Map<String, String> mdc = (mdcCopy != null && !mdcCopy.isEmpty())
                    ? new LinkedHashMap<>(mdcCopy)
                    : Collections.emptyMap();
            return RetryContextSnapshotPayload.builder()
                    .mdc(mdc)
                    .traceId(mdc.get(TRACE_ID_KEY))
                    .userId(UserContextHolder.getExplicit())
                    .tenantId(TenantContextHolder.getExplicit())
                    .ignoreTenant(TenantContextHolder.isIgnoreTenant())
                    .capturedAtEpochMillis(System.currentTimeMillis())
                    .build();
        } finally {
            previous.restore();
        }
    }

    private AsyncContext.ContextSnapshot fromPayload(RetryContextSnapshotPayload payload) {
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            if (payload.getMdc() != null && !payload.getMdc().isEmpty()) {
                MDC.setContextMap(payload.getMdc());
            } else {
                MDC.clear();
            }
            if (StringUtils.hasText(payload.getTraceId()) && !StringUtils.hasText(MDC.get(TRACE_ID_KEY))) {
                MDC.put(TRACE_ID_KEY, payload.getTraceId());
            }
            UserContextHolder.clear();
            if (StringUtils.hasText(payload.getUserId())) {
                UserContextHolder.set(payload.getUserId());
            }
            TenantContextHolder.clear();
            if (StringUtils.hasText(payload.getTenantId())) {
                TenantContextHolder.setTenantId(payload.getTenantId());
            }
            if (payload.isIgnoreTenant()) {
                TenantContextHolder.setIgnoreTenant(true);
            }
            return AsyncContext.ContextSnapshot.capture();
        } finally {
            previous.restore();
        }
    }
}
