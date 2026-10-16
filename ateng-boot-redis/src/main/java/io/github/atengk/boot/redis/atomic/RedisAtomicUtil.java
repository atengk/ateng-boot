package io.github.atengk.boot.redis.atomic;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
        Long value = increment(key, delta);
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            redisTemplate.expire(key, timeout);
        }
        return value;
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

        Long seq = redisTemplate.opsForValue().increment(key, 1L);
        if (seq != null && seq == 1L) {
            redisTemplate.expire(key, DAILY_SEQ_RETENTION);
        }

        String format = "%0" + length + "d";
        return prefix + dateStr + String.format(format, seq != null ? seq : 1L);
    }

    /**
     * 滑动窗口流控校验（判断当前请求在滑动时间窗口内是否超限，未超限则自动计入本次访问）
     * 基于 Redis Sorted Set 积分窗口实现真正的毫秒级平滑时间滑动，杜绝固定窗口突刺与临界双倍流量
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

        // 1. 移除窗口外的历史过期项
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // 2. 统计窗口内现存请求数
        Long count = redisTemplate.opsForZSet().zCard(key);
        if (count != null && count >= maxRequests) {
            return false;
        }

        // 3. 计入本次请求
        String member = now + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        redisTemplate.opsForZSet().add(key, member, (double) now);
        redisTemplate.expire(key, window.plusSeconds(10));
        return true;
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
