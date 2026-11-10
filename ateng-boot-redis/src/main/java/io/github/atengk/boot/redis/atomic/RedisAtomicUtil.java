package io.github.atengk.boot.redis.atomic;

import io.github.atengk.boot.redis.properties.RedisProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/**
 * 分布式原子计数、发号与滑动窗口工具类
 * 支持 Spring Bean 依赖注入与全局静态门面双重调用范式，
 * 封装带过期时间原子自增、CAS、跨天带防孤儿 TTL 业务流水号及精准毫秒滑动窗口流控等高阶原子原语。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisAtomicUtil {

    private static final StringRedisSerializer STRING_SERIALIZER = new StringRedisSerializer();

    private static final String DEFAULT_DATE_PATTERN = "yyyyMMdd";

    /**
     * 流水号 Key 默认保留兜底 TTL（2 天），防止历史废弃 Key 持续常驻内存空间泄露
     */
    private static final Duration DAILY_SEQ_RETENTION = Duration.ofDays(2);

    /**
     * 原子递增并支持首次初始化设置 TTL 的 Lua 脚本
     */
    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current == tonumber(ARGV[1]) and tonumber(ARGV[2]) > 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return current
            """,
            Long.class
    );

    /**
     * 生成跨天自重置流水号并原子附加兜底 TTL 的 Lua 脚本
     */
    private static final RedisScript<Long> DAILY_SEQ_SCRIPT = new DefaultRedisScript<>(
            """
            local seq = redis.call('INCR', KEYS[1])
            if seq == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return seq
            """,
            Long.class
    );

    /**
     * 基于 ZSet 毫秒时间戳的滑动时间窗口限流 Lua 脚本
     * ARGV[1] = 当前时间戳 (now)
     * ARGV[2] = 窗口起始时间戳 (now - window)
     * ARGV[3] = 窗口允许的最大请求数 (maxRequests)
     * ARGV[4] = 频控 Key 的过期时间秒数 (expireSeconds)
     * ARGV[5] = 当前请求唯一成员标识 (member)
     */
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
            local currentCount = redis.call('ZCARD', KEYS[1])
            if currentCount < tonumber(ARGV[3]) then
                redis.call('ZADD', KEYS[1], ARGV[1], ARGV[5])
                redis.call('EXPIRE', KEYS[1], ARGV[4])
                return 1
            else
                return 0
            end
            """,
            Long.class
    );

    /**
     * 严格对称比较并交换 (CAS) 原子脚本
     * ARGV[1] = 是否校验期望值 (0: 不校验仅要求 key 不存在或无条件, 1: 校验期望值)
     * ARGV[2] = 期望值 (expect)
     * ARGV[3] = 目标值 (update)
     * ARGV[4] = 过期时长秒数 (expireSeconds, -1 表示保持不变)
     */
    private static final RedisScript<Long> CAS_SCRIPT = new DefaultRedisScript<>(
            """
            local hasExpect = tonumber(ARGV[1])
            local expectVal = ARGV[2]
            local update = ARGV[3]
            local expireSec = tonumber(ARGV[4])

            local current = redis.call('GET', KEYS[1])
            local matched = false

            if current == false then
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

    private static volatile RedisTemplate<String, Object> staticTemplate;
    private static volatile RedisProperties staticProperties;
    private static volatile RedisAtomicUtil staticInstance;

    public RedisAtomicUtil(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, null);
    }

    public RedisAtomicUtil(RedisTemplate<String, Object> redisTemplate, RedisProperties redisProperties) {
        RedisAtomicUtil.staticTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        RedisAtomicUtil.staticProperties = redisProperties;
        RedisAtomicUtil.staticInstance = this;
    }

    /**
     * 绑定静态门面实例（由自动装配或显式构建时调用）
     *
     * @param instance 实例
     */
    public static void init(RedisAtomicUtil instance) {
        RedisAtomicUtil.staticInstance = instance;
    }

    /**
     * 获取静态受管门面单例
     *
     * @return RedisAtomicUtil 实例
     */
    public static RedisAtomicUtil getInstance() {
        RedisAtomicUtil instance = staticInstance;
        Assert.state(instance != null, "RedisAtomicUtil尚未初始化，请确保已启用自动装配");
        return instance;
    }

    private static RedisTemplate<String, Object> getTemplate() {
        RedisTemplate<String, Object> template = staticTemplate;
        Assert.state(template != null, "RedisAtomicUtil尚未初始化，请确保已启用自动装配或通过构造器注入 RedisTemplate");
        return template;
    }

    /**
     * 格式化并附加全局 key-prefix 前缀
     */
    public static String formatKey(String key) {
        if (staticProperties == null || key == null) {
            return key;
        }
        String prefix = staticProperties.getKeyPrefix();
        if (!StringUtils.hasText(prefix)) {
            return key;
        }
        String trimmed = prefix.trim();
        if (!trimmed.endsWith(":")) {
            trimmed += ":";
        }
        if (key.startsWith(trimmed)) {
            return key;
        }
        return trimmed + key;
    }

    /**
     * 原子递增 1
     *
     * @param key 键
     * @return 递增后的值
     */
    public static Long increment(String key) {
        return increment(key, 1L);
    }

    /**
     * 原子递增 1（静态简写别名）
     *
     * @param key 键
     * @return 递增后的值
     */
    public static Long incr(String key) {
        return increment(key, 1L);
    }

    /**
     * 原子递增 delta
     *
     * @param key 键
     * @param delta 增量步长
     * @return 递增后的值
     */
    public static Long increment(String key, long delta) {
        Assert.hasText(key, "key must not be blank");
        return getTemplate().opsForValue().increment(formatKey(key), delta);
    }

    /**
     * 原子递增 delta（静态简写别名）
     *
     * @param key 键
     * @param delta 增量步长
     * @return 递增后的值
     */
    public static Long incr(String key, long delta) {
        return increment(key, delta);
    }

    /**
     * 原子递增 delta 并设置/续期过期时间
     *
     * @param key 键
     * @param delta 增量步长
     * @param timeout 过期时长
     * @return 递增后的值
     */
    public static Long increment(String key, long delta, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return increment(key, delta);
        }
        long seconds = Math.max(1, timeout.toSeconds());
        return getTemplate().execute(
                INCR_EXPIRE_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(formatKey(key)),
                String.valueOf(delta),
                String.valueOf(seconds)
        );
    }

    /**
     * 原子递增 delta 并设置/续期过期时间（静态简写别名）
     *
     * @param key 键
     * @param delta 增量步长
     * @param timeout 过期时长
     * @return 递增后的值
     */
    public static Long incr(String key, long delta, Duration timeout) {
        return increment(key, delta, timeout);
    }

    /**
     * 原子递增 delta 并设置/续期过期时间（别名方法）
     *
     * @param key 键
     * @param delta 增量步长
     * @param timeout 过期时长
     * @return 递增后的值
     */
    public static Long incrWithExpire(String key, long delta, Duration timeout) {
        return increment(key, delta, timeout);
    }

    /**
     * 原子递减 1
     *
     * @param key 键
     * @return 递减后的值
     */
    public static Long decrement(String key) {
        return decrement(key, 1L);
    }

    /**
     * 原子递减 1（静态简写别名）
     *
     * @param key 键
     * @return 递减后的值
     */
    public static Long decr(String key) {
        return decrement(key, 1L);
    }

    /**
     * 原子递减 delta
     *
     * @param key 键
     * @param delta 减量步长
     * @return 递减后的值
     */
    public static Long decrement(String key, long delta) {
        Assert.hasText(key, "key must not be blank");
        return getTemplate().opsForValue().decrement(formatKey(key), delta);
    }

    /**
     * 原子递减 delta（静态简写别名）
     *
     * @param key 键
     * @param delta 减量步长
     * @return 递减后的值
     */
    public static Long decr(String key, long delta) {
        return decrement(key, delta);
    }

    /**
     * 获取当前计数值
     *
     * @param key 键
     * @return 当前值，不存在返回 null
     */
    public static Long get(String key) {
        Assert.hasText(key, "key must not be blank");
        Object val = getTemplate().opsForValue().get(formatKey(key));
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
     * @param value 目标数值
     */
    public static void set(String key, long value) {
        Assert.hasText(key, "key must not be blank");
        getTemplate().opsForValue().set(formatKey(key), value);
    }

    /**
     * 设置计数值并指定过期时间
     *
     * @param key 键
     * @param value 目标数值
     * @param timeout 过期时长
     */
    public static void set(String key, long value, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            getTemplate().opsForValue().set(formatKey(key), value, timeout);
        } else {
            getTemplate().opsForValue().set(formatKey(key), value);
        }
    }

    /**
     * 原子比较置换操作 (CAS)
     *
     * @param key 键
     * @param expect 期望值（允许为 null，表示要求键不存在时才初始化）
     * @param update 新值
     * @return 是否置换成功
     */
    public static boolean compareAndSet(String key, Long expect, Long update) {
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
    public static boolean compareAndSet(String key, Long expect, Long update, Duration timeout) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(update, "update must not be null");
        long expireSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? Math.max(1, timeout.toSeconds()) : -1L;
        String hasExpect = expect != null ? "1" : "0";
        String expectVal = expect != null ? String.valueOf(expect) : "0";
        Long result = getTemplate().execute(
                CAS_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(formatKey(key)),
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
    public static boolean compareAndSet(String key, long expect, long update) {
        return compareAndSet(key, Long.valueOf(expect), Long.valueOf(update), null);
    }

    /**
     * 原子设置新值并返回旧值 (getAndSet)
     *
     * @param key 键
     * @param update 新值
     * @return 更新前的旧值，若不存在返回 null
     */
    public static Long getAndSet(String key, long update) {
        return getAndSet(key, Long.valueOf(update));
    }

    /**
     * 原子设置新值并返回旧值 (getAndSet)
     *
     * @param key 键
     * @param update 新值
     * @return 更新前的旧值，若不存在返回 null
     */
    public static Long getAndSet(String key, Long update) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(update, "update must not be null");
        Object oldVal = getTemplate().opsForValue().getAndSet(formatKey(key), update);
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
    public static String generateDailySeq(String prefix, int length) {
        return generateDailySeq(prefix, DEFAULT_DATE_PATTERN, length);
    }

    /**
     * 生成按日重置的业务递增流水号（静态简写别名）
     *
     * @param prefix 业务前缀
     * @param length 尾部序列号数字位宽（不足左补零）
     * @return 格式化后的完整流水号
     */
    public static String dailySeq(String prefix, int length) {
        return generateDailySeq(prefix, length);
    }

    /**
     * 生成自定义日期的业务递增流水号
     *
     * @param prefix 业务前缀
     * @param datePattern 日期格式（如 yyyyMMdd、yyyyMM）
     * @param length 尾部数字位宽（不足左补零）
     * @return 格式化后的完整流水号
     */
    public static String generateDailySeq(String prefix, String datePattern, int length) {
        Assert.hasText(prefix, "prefix must not be blank");
        Assert.hasText(datePattern, "datePattern must not be blank");
        Assert.isTrue(length > 0, "length must be greater than 0");

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern));
        String rawKey = "seq:" + prefix + ":" + dateStr;
        String key = formatKey(rawKey);

        Long seq = getTemplate().execute(
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
     * 生成自定义日期的业务递增流水号（静态简写别名）
     *
     * @param prefix 业务前缀
     * @param datePattern 日期格式（如 yyyyMMdd、yyyyMM）
     * @param length 尾部数字位宽（不足左补零）
     * @return 格式化后的完整流水号
     */
    public static String dailySeq(String prefix, String datePattern, int length) {
        return generateDailySeq(prefix, datePattern, length);
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
    public static boolean slidingWindowCheck(String key, int maxRequests, Duration window) {
        Assert.hasText(key, "key must not be blank");
        Assert.isTrue(maxRequests > 0, "maxRequests must be greater than 0");
        Assert.notNull(window, "window must not be null");

        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        long expireSeconds = Math.max(window.toSeconds() + 10, 10);
        String member = now + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Long result = getTemplate().execute(
                SLIDING_WINDOW_SCRIPT,
                STRING_SERIALIZER,
                null,
                Collections.singletonList(formatKey(key)),
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
    public static boolean isAllowedBySlidingWindow(String key, int maxRequests, Duration window) {
        return slidingWindowCheck(key, maxRequests, window);
    }

    /**
     * 获取指定滑动时间窗口内的现存请求数
     *
     * @param key 频控键
     * @param window 时间窗口
     * @return 窗口内请求数量
     */
    public static long getSlidingWindowCount(String key, Duration window) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(window, "window must not be null");

        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        String formattedKey = formatKey(key);

        getTemplate().opsForZSet().removeRangeByScore(formattedKey, 0, windowStart);
        Long count = getTemplate().opsForZSet().zCard(formattedKey);
        return count != null ? count : 0L;
    }

    // ==========================================
    // 全局静态委托兼容别名方法 (Static Facade Aliases)
    // ==========================================

    public static Long incrementStatic(String key) {
        return increment(key);
    }

    public static Long incrementStatic(String key, long delta) {
        return increment(key, delta);
    }

    public static Long incrementStatic(String key, long delta, Duration timeout) {
        return increment(key, delta, timeout);
    }

    public static Long incrWithExpireStatic(String key, long delta, Duration timeout) {
        return incrWithExpire(key, delta, timeout);
    }

    public static Long decrementStatic(String key) {
        return decrement(key);
    }

    public static Long decrementStatic(String key, long delta) {
        return decrement(key, delta);
    }

    public static Long getStatic(String key) {
        return get(key);
    }

    public static void setStatic(String key, long value) {
        set(key, value);
    }

    public static void setStatic(String key, long value, Duration timeout) {
        set(key, value, timeout);
    }

    public static boolean compareAndSetStatic(String key, Long expect, Long update) {
        return compareAndSet(key, expect, update);
    }

    public static boolean compareAndSetStatic(String key, Long expect, Long update, Duration timeout) {
        return compareAndSet(key, expect, update, timeout);
    }

    public static boolean compareAndSetStatic(String key, long expect, long update) {
        return compareAndSet(key, expect, update);
    }

    public static Long getAndSetStatic(String key, long update) {
        return getAndSet(key, update);
    }

    public static Long getAndSetStatic(String key, Long update) {
        return getAndSet(key, update);
    }

    public static String generateDailySeqStatic(String prefix, int length) {
        return generateDailySeq(prefix, length);
    }

    public static String generateDailySeqStatic(String prefix, String datePattern, int length) {
        return generateDailySeq(prefix, datePattern, length);
    }

    public static boolean slidingWindowCheckStatic(String key, int maxRequests, Duration window) {
        return slidingWindowCheck(key, maxRequests, window);
    }

    public static boolean isAllowedBySlidingWindowStatic(String key, int maxRequests, Duration window) {
        return isAllowedBySlidingWindow(key, maxRequests, window);
    }

    public static long getSlidingWindowCountStatic(String key, Duration window) {
        return getSlidingWindowCount(key, window);
    }
}
