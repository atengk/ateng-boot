package io.github.atengk.boot.redis.atomic;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/**
 * 分布式原子计数、发号与滑动窗口工具
 * 提供分布式原子自增自减、基于日历的唯一序列号发号（支持按日重置与自动归档）以及基于 ZSet 的滑动窗口频控计数
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisAtomicUtil {

    public static final String DEFAULT_DATE_PATTERN = "yyyyMMdd";
    private static final Duration DAILY_SEQ_RETENTION = Duration.ofDays(2);
    private static final StringRedisSerializer STRING_SERIALIZER = new StringRedisSerializer();

    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local val = redis.call('INCRBY', KEYS[1], ARGV[1])
            if tonumber(ARGV[2]) > 0 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return val
            """,
            Long.class
    );

    private static final RedisScript<Long> DAILY_SEQ_SCRIPT = new DefaultRedisScript<>(
            """
            local val = redis.call('INCRBY', KEYS[1], 1)
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return val
            """,
            Long.class
    );

    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowStart = tonumber(ARGV[2])
            local maxRequests = tonumber(ARGV[3])
            local expireSeconds = tonumber(ARGV[4])
            local member = ARGV[5]

            redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
            local currentCount = redis.call('ZCARD', key)

            if currentCount < maxRequests then
                redis.call('ZADD', key, now, member)
                redis.call('EXPIRE', key, expireSeconds)
                return 1
            else
                return 0
            end
            """,
            Long.class
    );

    private static final RedisScript<Long> CAS_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            local hasExpect = tonumber(ARGV[1])
            local expectVal = ARGV[2]
            local update = ARGV[3]
            local expireSec = tonumber(ARGV[4] or -1)

            local matched = false
            if not current then
                if hasExpect == 0 then
                    matched = true
                end
            else
                if hasExpect == 1 and tonumber(current) == tonumber(expectVal) then
                    matched = true
                end
            end

            if matched then
                redis.call('SET', KEYS[1], update)
                if expireSec > 0 then
                    redis.call('EXPIRE', KEYS[1], expireSec)
                end
                return 1
            else
                return 0
            end
            """,
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisAtomicUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    /**
     * 原子递增 1
     *
     * @param key 键
     * @return 递增后的值
     */
    public Long increment(String key) {
        return increment(key, 1L);
    }

    /**
     * 原子递增 delta
     *
     * @param key 键
     * @param delta 增量步长
     * @return 递增后的值
     */
    public Long increment(String key, long delta) {
        Assert.hasText(key, "key must not be blank");
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 原子递增 delta 并设置/续期过期时间
     *
     * @param key 键
     * @param delta 增量步长
     * @param timeout 过期时长
     * @return 递增后的值
     */
    public Long increment(String key, long delta, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return increment(key, delta);
        }
        long seconds = Math.max(1, timeout.toSeconds());
        return redisTemplate.execute(
                INCR_EXPIRE_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(key),
                String.valueOf(delta),
                String.valueOf(seconds)
        );
    }

    /**
     * 原子递减 1
     *
     * @param key 键
     * @return 递减后的值
     */
    public Long decrement(String key) {
        return decrement(key, 1L);
    }

    /**
     * 原子递减 delta
     *
     * @param key 键
     * @param delta 减量步长
     * @return 递减后的值
     */
    public Long decrement(String key, long delta) {
        Assert.hasText(key, "key must not be blank");
        return redisTemplate.opsForValue().decrement(key, delta);
    }

    /**
     * 获取当前计数值
     *
     * @param key 键
     * @return 当前值，不存在返回 null
     */
    public Long get(String key) {
        Assert.hasText(key, "key must not be blank");
        Object val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Number num) {
            return num.longValue();
        }
        return Long.parseLong(String.valueOf(val));
    }

    /**
     * 设置计数值
     *
     * @param key 键
     * @param value 计数值
     */
    public void set(String key, long value) {
        Assert.hasText(key, "key must not be blank");
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置计数值并指定过期时间
     *
     * @param key 键
     * @param value 计数值
     * @param timeout 过期时间
     */
    public void set(String key, long value, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(timeout, "timeout must not be null");
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    /**
     * 原子比较置换操作 (CAS - Compare-And-Set)
     * 当且仅当 Redis 中的当前值等于 expect 时，原子更新为 update
     * 支持 expect 为 null（即当 key 不存在时初始化设置）
     *
     * @param key 键
     * @param expect 期望值（允许为 null，表示期望 key 不存在）
     * @param update 新值
     * @return 是否置换成功
     */
    public boolean compareAndSet(String key, Long expect, Long update) {
        return compareAndSet(key, expect, update, null);
    }

    /**
     * 原子比较置换操作 (CAS) 并设置/刷新过期时间
     *
     * @param key 键
     * @param expect 期望值（允许为 null）
     * @param update 新值
     * @param timeout 过期时长
     * @return 是否置换成功
     */
    public boolean compareAndSet(String key, Long expect, Long update, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(update, "update must not be null");
        long expireSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? Math.max(1, timeout.toSeconds()) : -1L;
        String hasExpect = expect != null ? "1" : "0";
        String expectVal = expect != null ? String.valueOf(expect) : "0";
        Long result = redisTemplate.execute(
                CAS_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(key),
                hasExpect,
                expectVal,
                String.valueOf(update),
                String.valueOf(expireSeconds)
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 原子比较置换操作 (基本类型重载)
     *
     * @param key 键
     * @param expect 期望值
     * @param update 新值
     * @return 是否置换成功
     */
    public boolean compareAndSet(String key, long expect, long update) {
        return compareAndSet(key, Long.valueOf(expect), Long.valueOf(update), null);
    }

    /**
     * 原子设置新值并返回旧值 (getAndSet)
     *
     * @param key 键
     * @param update 新值
     * @return 更新前的旧值，若不存在返回 null
     */
    public Long getAndSet(String key, long update) {
        return getAndSet(key, Long.valueOf(update));
    }

    /**
     * 原子设置新值并返回旧值 (getAndSet)
     *
     * @param key 键
     * @param update 新值
     * @return 更新前的旧值，若不存在返回 null
     */
    public Long getAndSet(String key, Long update) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(update, "update must not be null");
        Object oldVal = redisTemplate.opsForValue().getAndSet(key, update);
        if (oldVal == null) {
            return null;
        }
        if (oldVal instanceof Number num) {
            return num.longValue();
        }
        return Long.parseLong(String.valueOf(oldVal));
    }

    /**
     * 生成按日重置的业务递增流水号（格式：{prefix}{yyyyMMdd}{%0length d}）
     * 例如 prefix="ORD", length=6 -> ORD20261016000001
     * 并在首日创建时自动附加 48 小时兜底过期时间，杜绝过期废弃 key 永久常驻
     *
     * @param prefix 业务前缀
     * @param length 尾部序列号数字位宽（不足左补零）
     * @return 格式化后的完整流水号
     */
    public String generateDailySeq(String prefix, int length) {
        return generateDailySeq(prefix, DEFAULT_DATE_PATTERN, length);
    }

    /**
     * 生成自定义日期的业务递增流水号
     *
     * @param prefix 业务前缀
     * @param datePattern 日期格式（如 yyyyMMdd、yyyyMM）
     * @param length 尾部数字位宽（不足左补零）
     * @return 格式化后的完整流水号
     */
    public String generateDailySeq(String prefix, String datePattern, int length) {
        Assert.hasText(prefix, "prefix must not be blank");
        Assert.hasText(datePattern, "datePattern must not be blank");
        Assert.isTrue(length > 0, "length must be greater than 0");

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern));
        String key = "seq:" + prefix + ":" + dateStr;

        Long seq = redisTemplate.execute(
                DAILY_SEQ_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(key),
                String.valueOf(DAILY_SEQ_RETENTION.toSeconds())
        );

        String format = "%0" + length + "d";
        return prefix + dateStr + String.format(format, seq != null ? seq : 1L);
    }

    /**
     * 滑动窗口流控校验（判断当前请求在滑动时间窗口内是否超限，未超限则自动计入本次访问）
     * 基于 Redis Lua 脚本原子执行 ZSet 历史淘汰、当前计数判定与本次记录入库，彻底杜绝并发竞态穿透
     *
     * @param key 频控键（如限流维度 ip/userId）
     * @param maxRequests 窗口内最大允许请求数
     * @param window 滑动时间窗口时长
     * @return true 表示在阈值内放行；false 表示超限拦截
     */
     public boolean slidingWindowCheck(String key, int maxRequests, Duration window) {
        Assert.hasText(key, "key must not be blank");
        Assert.isTrue(maxRequests > 0, "maxRequests must be greater than 0");
        Assert.notNull(window, "window must not be null");

        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        long expireSeconds = Math.max(window.toSeconds() + 10, 10);
        String member = now + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Long result = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(windowStart),
                String.valueOf(maxRequests),
                String.valueOf(expireSeconds),
                member
        );
        return result != null && result == 1L;
    }

    /**
     * 滑动窗口流控校验别名方法（语义同 {@link #slidingWindowCheck(String, int, Duration)}）
     *
     * @param key 频控键
     * @param maxRequests 窗口内最大允许请求数
     * @param window 滑动时间窗口时长
     * @return true 表示在阈值内放行；false 表示超限拦截
     */
    public boolean isAllowedBySlidingWindow(String key, int maxRequests, Duration window) {
        return slidingWindowCheck(key, maxRequests, window);
    }

    /**
     * 获取指定滑动时间窗口内的现存请求数
     *
     * @param key 频控键
     * @param window 时间窗口
     * @return 窗口内请求数量
     */
    public long getSlidingWindowCount(String key, Duration window) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(window, "window must not be null");

        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();

        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }
}
