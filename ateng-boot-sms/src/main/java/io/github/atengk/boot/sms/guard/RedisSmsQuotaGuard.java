package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 原子 Lua 脚本的分布式单渠道配额守卫
 * <p>
 * 支持按日键自动过期清零 ({@link SmsQuotaResetCycle#DAILY}) 与包干累计键 ({@link SmsQuotaResetCycle#TOTAL})；
 * 当 Redis 通信异常时记录告警并安全降级放行，保障主链路不雪崩。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class RedisSmsQuotaGuard implements SmsQuotaGuard {

    private static final String DAILY_KEY_PREFIX = "ateng:sms:quota:daily:";
    private static final String TOTAL_KEY_PREFIX = "ateng:sms:quota:total:";
    private static final long DAILY_KEY_TTL_SECONDS = 172_800L;

    private static final String QUOTA_LUA = """
            local key = KEYS[1]
            local count = tonumber(ARGV[1])
            local maxQuota = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            local current = redis.call('GET', key)
            local currNum = 0
            if current then
                currNum = tonumber(current)
            end
            if maxQuota > 0 and (currNum + count > maxQuota) then
                return -1
            end
            local newTotal = redis.call('INCRBY', key, count)
            if currNum == 0 and ttl > 0 then
                redis.call('EXPIRE', key, ttl)
            end
            return newTotal
            """;

    private static final String RELEASE_LUA = """
            local key = KEYS[1]
            local count = tonumber(ARGV[1])
            local current = redis.call('GET', key)
            if not current then
                return 0
            end
            local currNum = tonumber(current)
            if currNum <= count then
                redis.call('SET', key, '0')
                return 0
            end
            return redis.call('DECRBY', key, count)
            """;

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final DefaultRedisScript<Long> redisScript;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisSmsQuotaGuard(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.redisScript = new DefaultRedisScript<>(QUOTA_LUA, Long.class);
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    }

    public RedisSmsQuotaGuard(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemDefaultZone());
    }

    @Override
    public boolean tryAcquire(SmsChannel channel, int count) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0 || count <= 0) {
            return true;
        }
        String name = channel.getName().trim();
        SmsQuotaResetCycle cycle = channel.getQuotaResetCycle() != null
                ? channel.getQuotaResetCycle()
                : SmsQuotaResetCycle.DAILY;
        String key = buildKey(name, cycle);
        long ttl = cycle == SmsQuotaResetCycle.DAILY ? DAILY_KEY_TTL_SECONDS : 0L;

        try {
            Long result = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(count),
                    String.valueOf(channel.getMaxQuota()),
                    String.valueOf(ttl)
            );
            return result == null || result != -1L;
        } catch (Exception ex) {
            log.warn("[RedisSmsQuotaGuard] Redis 渠道配额判定异常，降级放行 -> 渠道: {}, 错误: {}", name, ex.getMessage());
            return true;
        }
    }

    @Override
    public void release(SmsChannel channel, int count) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0 || count <= 0) {
            return;
        }
        String name = channel.getName().trim();
        SmsQuotaResetCycle cycle = channel.getQuotaResetCycle() != null
                ? channel.getQuotaResetCycle()
                : SmsQuotaResetCycle.DAILY;
        String key = buildKey(name, cycle);
        try {
            redisTemplate.execute(releaseScript, Collections.singletonList(key), String.valueOf(count));
        } catch (Exception ex) {
            log.warn("[RedisSmsQuotaGuard] Redis 渠道配额释放异常 -> 渠道: {}, 错误: {}", name, ex.getMessage());
        }
    }

    @Override
    public boolean isQuotaAvailable(SmsChannel channel) {
        if (channel == null || channel.getName() == null || channel.getMaxQuota() <= 0) {
            return true;
        }
        return getUsedQuota(channel) < channel.getMaxQuota();
    }

    @Override
    public long getUsedQuota(String channelName, SmsQuotaResetCycle cycle) {
        if (channelName == null || channelName.isBlank()) {
            return 0L;
        }
        SmsQuotaResetCycle effectiveCycle = cycle != null ? cycle : SmsQuotaResetCycle.DAILY;
        String key = buildKey(channelName.trim(), effectiveCycle);
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val != null ? Long.parseLong(val) : 0L;
        } catch (Exception ex) {
            log.warn("[RedisSmsQuotaGuard] 查询渠道配额异常，降级返回 0 -> 渠道: {}, 错误: {}", channelName, ex.getMessage());
            return 0L;
        }
    }

    @Override
    public void resetQuota(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        String name = channelName.trim();
        try {
            redisTemplate.delete(List.of(
                    buildKey(name, SmsQuotaResetCycle.DAILY),
                    buildKey(name, SmsQuotaResetCycle.TOTAL)
            ));
        } catch (Exception ex) {
            log.warn("[RedisSmsQuotaGuard] 重置渠道配额异常 -> 渠道: {}, 错误: {}", name, ex.getMessage());
        }
    }

    @Override
    public void resetAll() {
        try {
            ScanOptions options = ScanOptions.scanOptions().match("ateng:sms:quota:*").count(500).build();
            List<String> keysToDelete = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor != null && cursor.hasNext()) {
                    keysToDelete.add(cursor.next());
                }
            }
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
            }
        } catch (Exception ex) {
            log.warn("[RedisSmsQuotaGuard] 批量清理渠道配额异常: {}", ex.getMessage());
        }
    }

    private String buildKey(String channelName, SmsQuotaResetCycle cycle) {
        if (cycle == SmsQuotaResetCycle.TOTAL) {
            return TOTAL_KEY_PREFIX + channelName;
        }
        String today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return DAILY_KEY_PREFIX + channelName + ":" + today;
    }
}
