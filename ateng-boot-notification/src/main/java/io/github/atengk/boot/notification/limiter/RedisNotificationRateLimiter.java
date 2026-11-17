package io.github.atengk.boot.notification.limiter;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于 Spring Data Redis 与单次原子 Lua 脚本的双滑动窗口分布式频控器
 * 协同校验分钟级滑动窗口配额与秒级突发防卫配额（采用 Redis Cluster 兼容 Hash Tag 保证多键落入同一槽位），支持虚拟线程友好的非 Pinning 微退避排队与 fail-open/fail-closed 容灾降级
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
public class RedisNotificationRateLimiter extends AbstractNotificationRateLimiter {

    private static final String DEFAULT_KEY_PREFIX = "ateng:notification:ratelimit:";
    private static final long DEFAULT_BURST_WINDOW_MS = 1000L;
    private static final long MIN_BACKOFF_MS = 25L;
    private static final long MAX_BACKOFF_MS = 200L;
    private static final int SCAN_DELETE_BATCH_SIZE = 128;

    /**
     * 单次原子校验分钟级与秒级突发双 ZSET 滑动窗口 Lua 脚本
     * KEYS[1]: 分钟级 ZSET Key ({prefix}{channel}:minute)
     * KEYS[2]: 秒级突发 ZSET Key ({prefix}{channel}:second)
     * ARGV[1..6]: minute_limit, minute_window_ms, burst_limit, burst_window_ms, now_ms, member_id
     * 返回值: 1 表示成功抢占双窗口配额; <= 0 表示超限（负数绝对值为最近配额释放估算毫秒数）
     */
    private static final String DUAL_SLIDING_WINDOW_LUA_SCRIPT = """
            local minute_key = KEYS[1]
            local second_key = KEYS[2]
            local minute_limit = tonumber(ARGV[1])
            local minute_window_ms = tonumber(ARGV[2])
            local burst_limit = tonumber(ARGV[3])
            local burst_window_ms = tonumber(ARGV[4])
            local now_ms = tonumber(ARGV[5])
            local member_id = ARGV[6]

            if not minute_limit or minute_limit <= 0 or not minute_window_ms or minute_window_ms <= 0 then
                return 0
            end

            redis.call('ZREMRANGEBYSCORE', minute_key, '-inf', now_ms - minute_window_ms)
            local minute_count = redis.call('ZCARD', minute_key)

            local burst_count = 0
            if burst_limit and burst_limit > 0 then
                redis.call('ZREMRANGEBYSCORE', second_key, '-inf', now_ms - burst_window_ms)
                burst_count = redis.call('ZCARD', second_key)
            end

            local minute_ok = minute_count < minute_limit
            local burst_ok = (not burst_limit) or (burst_limit <= 0) or (burst_count < burst_limit)

            if minute_ok and burst_ok then
                redis.call('ZADD', minute_key, now_ms, member_id)
                redis.call('PEXPIRE', minute_key, minute_window_ms + 1000)
                if burst_limit and burst_limit > 0 then
                    redis.call('ZADD', second_key, now_ms, member_id)
                    redis.call('PEXPIRE', second_key, burst_window_ms + 1000)
                end
                return 1
            end

            local wait_ms = 0
            if not minute_ok then
                local oldest = redis.call('ZRANGE', minute_key, 0, 0, 'WITHSCORES')
                if oldest[2] then
                    wait_ms = math.max(wait_ms, minute_window_ms - (now_ms - tonumber(oldest[2])))
                end
            end
            if not burst_ok then
                local oldest_burst = redis.call('ZRANGE', second_key, 0, 0, 'WITHSCORES')
                if oldest_burst[2] then
                    wait_ms = math.max(wait_ms, burst_window_ms - (now_ms - tonumber(oldest_burst[2])))
                end
            end
            if wait_ms > 0 then
                return -math.ceil(wait_ms)
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> acquireScript;
    private final Duration windowDuration;
    private final String keyPrefix;

    public RedisNotificationRateLimiter(StringRedisTemplate redisTemplate, NotificationRateLimitProperties properties) {
        this(redisTemplate, properties, Duration.ofMinutes(1));
    }

    public RedisNotificationRateLimiter(StringRedisTemplate redisTemplate,
                                        NotificationRateLimitProperties properties,
                                        Duration windowDuration) {
        super(properties);
        BusinessAssert.notNull(redisTemplate, "StringRedisTemplate 不能为空");
        this.redisTemplate = redisTemplate;
        this.acquireScript = new DefaultRedisScript<>(DUAL_SLIDING_WINDOW_LUA_SCRIPT, Long.class);
        if (windowDuration != null && (windowDuration.isNegative() || windowDuration.isZero())) {
            log.warn("分布式协同通知频控窗口时长非法 ({})，已平滑回退为默认 1 分钟窗口", windowDuration);
            this.windowDuration = Duration.ofMinutes(1);
        } else {
            this.windowDuration = windowDuration != null ? windowDuration : Duration.ofMinutes(1);
        }
        this.keyPrefix = normalizePrefix(this.properties.getRedisKeyPrefix());
    }

    @Override
    public boolean tryAcquire(NotificationChannel channel, Duration maxWaitDuration) {
        String channelName = validateAndNormalizeChannelName(channel);

        if (!properties.isEnabled()) {
            return true;
        }

        int minutePermits = resolvePermitsPerMinute(channel);
        if (minutePermits <= 0) {
            return false;
        }
        int burstPermits = resolveBurstPermitsPerSecond(channel);
        long maxWaitNanos = (maxWaitDuration != null && !maxWaitDuration.isNegative())
                ? maxWaitDuration.toNanos()
                : 0L;

        List<String> keys = List.of(buildMinuteKey(channelName), buildSecondKey(channelName));

        if (maxWaitNanos <= 0L) {
            return executeAcquireLua(channelName, keys, minutePermits, burstPermits, Duration.ZERO) == 1L;
        }

        long deadlineNanos = System.nanoTime() + maxWaitNanos;
        long fallbackBackoffMs = MIN_BACKOFF_MS;

        while (true) {
            long scriptResult = executeAcquireLua(channelName, keys, minutePermits, burstPermits, maxWaitDuration);
            if (scriptResult == 1L) {
                return true;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return false;
            }

            long suggestedWaitMs = scriptResult < 0L ? -scriptResult : fallbackBackoffMs;
            long boundedWaitMs = Math.min(MAX_BACKOFF_MS, Math.max(MIN_BACKOFF_MS, suggestedWaitMs));
            long sleepNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(boundedWaitMs));
            if (sleepNanos <= 0L) {
                return false;
            }

            // LockSupport.parkNanos 避免 synchronized 锁住 Carrier，JDK 21+ 虚拟线程可无阻塞安全让出调度器
            LockSupport.parkNanos(sleepNanos);
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            fallbackBackoffMs = Math.min(MAX_BACKOFF_MS, fallbackBackoffMs * 2L);
        }
    }

    @Override
    public void reset(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        String normalizedChannel = channelName.trim();
        try {
            redisTemplate.delete(List.of(buildMinuteKey(normalizedChannel), buildSecondKey(normalizedChannel)));
        } catch (Exception ex) {
            if (properties.isFailOpenOnRedisError()) {
                log.warn("分布式协同通知频控重置通道 [{}] 异常，触发 fail-open 降级忽略: {}",
                        normalizedChannel, ex.getMessage(), ex);
                return;
            }
            throw new NotificationException(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED,
                    "分布式协同通知频控重置通道 [" + normalizedChannel + "] 失败（fail-closed）: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void resetAll() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(keyPrefix + "*")
                .count(500)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            if (cursor == null) {
                return;
            }
            List<String> batch = new ArrayList<>(SCAN_DELETE_BATCH_SIZE);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_DELETE_BATCH_SIZE) {
                    redisTemplate.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        } catch (Exception ex) {
            if (properties.isFailOpenOnRedisError()) {
                log.warn("分布式协同通知频控全量重置异常，触发 fail-open 降级忽略: {}", ex.getMessage(), ex);
                return;
            }
            throw new NotificationException(NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED,
                    "分布式协同通知频控全量重置失败（fail-closed）: " + ex.getMessage(), ex);
        }
    }

    /**
     * 构建分钟级滑动窗口 ZSET Redis 键（使用 Cluster Hash Tag 保证多键落入同一槽位）
     *
     * @param channelName 渠道名称
     * @return 分钟级 Redis 键
     */
    public String buildMinuteKey(String channelName) {
        return keyPrefix + "{" + validateChannelName(channelName) + "}:minute";
    }

    /**
     * 构建秒级突发滑动窗口 ZSET Redis 键（使用 Cluster Hash Tag 保证多键落入同一槽位）
     *
     * @param channelName 渠道名称
     * @return 秒级突发 Redis 键
     */
    public String buildSecondKey(String channelName) {
        return keyPrefix + "{" + validateChannelName(channelName) + "}:second";
    }

    private long executeAcquireLua(String channelName,
                                   List<String> keys,
                                   int minutePermits,
                                   int burstPermits,
                                   Duration waitDuration) {
        long nowMs = System.currentTimeMillis();
        long minuteWindowMs = Math.max(1L, windowDuration.toMillis());
        long burstWindowMs = Math.min(minuteWindowMs, DEFAULT_BURST_WINDOW_MS);
        String memberId = nowMs + ":" + IdUtil.fastSimpleUUID();

        try {
            Long result = redisTemplate.execute(
                    acquireScript,
                    keys,
                    String.valueOf(minutePermits),
                    String.valueOf(minuteWindowMs),
                    String.valueOf(Math.max(0, burstPermits)),
                    String.valueOf(burstWindowMs),
                    String.valueOf(nowMs),
                    memberId
            );
            return result != null ? result : 0L;
        } catch (Exception ex) {
            if (properties.isFailOpenOnRedisError()) {
                log.warn("分布式协同通知频控 Lua 执行异常，触发 fail-open 策略直接放行发信 [channel={}]: {}",
                        channelName, ex.getMessage(), ex);
                return 1L;
            }
            throw new NotificationRateLimitException(
                    channelName,
                    minutePermits,
                    waitDuration,
                    "分布式协同通知频控 Redis 执行异常（fail-closed） [channel=" + channelName + "]: " + ex.getMessage(),
                    ex
            );
        }
    }

    private static String normalizePrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        String trimmed = rawPrefix.trim();
        return trimmed.endsWith(":") ? trimmed : trimmed + ":";
    }
}
