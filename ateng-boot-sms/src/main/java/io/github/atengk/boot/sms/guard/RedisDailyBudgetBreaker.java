package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.event.SmsBudgetExceededEvent;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * 基于 Redis 与原子 Lua 脚本的分布式单日成本预算熔断器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class RedisDailyBudgetBreaker implements DailyBudgetBreaker {

    private static final String KEY_PREFIX = "ateng:sms:guard:budget:";
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local count = tonumber(ARGV[1])\n" +
            "local quota = tonumber(ARGV[2])\n" +
            "local current = redis.call('GET', key)\n" +
            "local currNum = 0\n" +
            "if current then\n" +
            "    currNum = tonumber(current)\n" +
            "end\n" +
            "if quota > 0 and (currNum + count > quota) then\n" +
            "    return -1\n" +
            "end\n" +
            "local newTotal = redis.call('INCRBY', key, count)\n" +
            "if currNum == 0 then\n" +
            "    redis.call('EXPIRE', key, 172800)\n" +
            "end\n" +
            "return newTotal\n";

    private final StringRedisTemplate redisTemplate;
    private final SmsGuardProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final DefaultRedisScript<Long> redisScript;

    public RedisDailyBudgetBreaker(StringRedisTemplate redisTemplate,
                                  SmsGuardProperties properties,
                                  ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.properties = properties != null ? properties : new SmsGuardProperties();
        this.eventPublisher = eventPublisher;
        this.redisScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    public RedisDailyBudgetBreaker(StringRedisTemplate redisTemplate, SmsGuardProperties properties) {
        this(redisTemplate, properties, null);
    }

    private String getTodayKey() {
        return KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Override
    public void checkAndIncrement(int count) throws SmsBudgetExceededException {
        if (count <= 0) {
            return;
        }
        long quota = getDailyMaxQuota();
        String key = getTodayKey();

        Long result;
        try {
            result = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(count),
                    String.valueOf(quota)
            );
        } catch (Exception ex) {
            log.warn("[RedisDailyBudgetBreaker] Redis 通信异常，降级记录日志: {}", ex.getMessage());
            return;
        }

        if (result != null && result == -1L) {
            long current = getCurrentDailyCount();
            log.error("[RedisDailyBudgetBreaker] 短信单日累计发信量触发硬熔断 (当前累计: {}, 拟发: {}, 配额上限: {})",
                    current, count, quota);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new SmsBudgetExceededEvent(this, quota, current, count));
            }
            throw new SmsBudgetExceededException(quota, current);
        }
    }

    @Override
    public long getDailyMaxQuota() {
        return properties.getDailyMaxQuota();
    }

    @Override
    public long getCurrentDailyCount() {
        try {
            String val = redisTemplate.opsForValue().get(getTodayKey());
            return val != null ? Long.parseLong(val) : 0L;
        } catch (Exception ex) {
            log.warn("[RedisDailyBudgetBreaker] 获取单日累计发信量异常: {}", ex.getMessage());
            return 0L;
        }
    }

    @Override
    public void reset() {
        try {
            redisTemplate.delete(getTodayKey());
        } catch (Exception ex) {
            log.warn("[RedisDailyBudgetBreaker] 重置单日预算计数器异常: {}", ex.getMessage());
        }
    }
}
