package io.github.atengk.boot.sms.repository;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 基于 Redis Hash 与原子 Lua 脚本的分布式短信验证码仓储
 * 严格防重放（成功原子删除）与防爆破（错误原子扣减、达上限原子冻结）
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class RedisSmsVerifyCodeRepository implements SmsVerifyCodeRepository {

    private static final String KEY_PREFIX = "ateng:sms:code:";

    private static final String VERIFY_LUA = """
            local key = KEYS[1]
            local inputCode = ARGV[1]
            local now = tonumber(ARGV[2])

            local exists = redis.call('EXISTS', key)
            if exists == 0 then
                return {-1, 0}
            end

            local frozen = redis.call('HGET', key, 'frozen')
            if frozen == '1' then
                return {-2, 0}
            end

            local actualCode = redis.call('HGET', key, 'code')
            if actualCode and string.lower(actualCode) == string.lower(inputCode) then
                redis.call('DEL', key)
                return {1, 0}
            else
                local remaining = tonumber(redis.call('HINCRBY', key, 'remainingAttempts', -1))
                if remaining <= 0 then
                    redis.call('HSET', key, 'frozen', '1')
                    return {-3, 0}
                else
                    return {-4, remaining}
                end
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> verifyScript;

    public RedisSmsVerifyCodeRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.verifyScript = new DefaultRedisScript<>();
        this.verifyScript.setScriptText(VERIFY_LUA);
        this.verifyScript.setResultType(List.class);
    }

    @Override
    public void checkCooldown(String phone, String bizType) {
        if (!org.springframework.util.StringUtils.hasText(phone)) {
            return;
        }
        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();
        String resendAfterStr = (String) redisTemplate.opsForHash().get(key, "resendAfter");
        if (resendAfterStr != null) {
            try {
                long resendAfter = Long.parseLong(resendAfterStr);
                if (now < resendAfter) {
                    long waitSeconds = Math.max(1, (resendAfter - now) / 1000);
                    log.warn("[RedisSmsVerifyCodeRepository] 验证码处于冷却期内，拦截重发 (还需等待: {} 秒)", waitSeconds);
                    throw new SmsRateLimitException("验证码发送过于频繁，请等待 " + waitSeconds + " 秒后再试", waitSeconds);
                }
            } catch (NumberFormatException ex) {
                log.warn("[RedisSmsVerifyCodeRepository] 解析冷却时间戳格式异常: {}", resendAfterStr);
            }
        }
    }

    @Override
    public void save(String phone, String bizType, String code, Duration ttl, Duration resendInterval, int maxRetryAttempts) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "验证码内容不能为空");

        // 1. 前置检查是否存在且在重发冷却期内
        checkCooldown(phone, bizType);

        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();

        // 2. 写入新验证码 Hash
        long expireSeconds = ttl != null ? ttl.toSeconds() : 300L;
        long resendAfter = now + (resendInterval != null ? resendInterval.toMillis() : 60_000L);

        Map<String, String> data = new HashMap<>(4);
        data.put("code", code);
        data.put("resendAfter", String.valueOf(resendAfter));
        data.put("remainingAttempts", String.valueOf(maxRetryAttempts > 0 ? maxRetryAttempts : 5));
        data.put("frozen", "0");

        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, Duration.ofSeconds(expireSeconds));
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean verify(String phone, String code, String bizType) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "待核验验证码不能为空");

        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();

        List<Long> result = (List<Long>) redisTemplate.execute(
                verifyScript,
                Collections.singletonList(key),
                code.trim(),
                String.valueOf(now)
        );

        if (result == null || result.isEmpty()) {
            throw new SmsVerifyCodeException("验证码不存在或已过期", 0, false);
        }

        long status = result.get(0);
        long remaining = result.size() > 1 ? result.get(1) : 0;

        if (status == 1L) {
            log.info("[RedisSmsVerifyCodeRepository] 验证码核销成功并原子删除 (phone: {}, bizType: {})", phone, bizType);
            return true;
        } else if (status == -1L) {
            throw new SmsVerifyCodeException("验证码不存在或已过期", 0, false);
        } else if (status == -2L) {
            throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
        } else if (status == -3L) {
            throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
        } else if (status == -4L) {
            throw new SmsVerifyCodeException("验证码错误，剩余重试次数: " + remaining, (int) remaining, false);
        }

        throw new SmsVerifyCodeException("验证码核验失败", 0, false);
    }

    @Override
    public boolean check(String phone, String code, String bizType) {
        if (!org.springframework.util.StringUtils.hasText(phone) || !org.springframework.util.StringUtils.hasText(code)) {
            return false;
        }
        String key = buildKey(phone, bizType);
        String frozen = (String) redisTemplate.opsForHash().get(key, "frozen");
        if ("1".equals(frozen)) {
            return false;
        }
        String actualCode = (String) redisTemplate.opsForHash().get(key, "code");
        return actualCode != null && actualCode.equalsIgnoreCase(code.trim());
    }

    @Override
    public void remove(String phone, String bizType) {
        if (phone != null) {
            redisTemplate.delete(buildKey(phone, bizType));
        }
    }

    private String buildKey(String phone, String bizType) {
        return KEY_PREFIX + phone + ":" + (bizType != null ? bizType.trim() : "default");
    }
}
