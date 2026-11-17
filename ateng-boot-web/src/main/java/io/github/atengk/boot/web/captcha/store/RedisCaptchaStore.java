package io.github.atengk.boot.web.captcha.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

/**
 * 基于 {@link StringRedisTemplate} 与原子 Lua 脚本的分布式图形验证码存储实现
 * 使用原子 GET+DEL 脚本兼容全版本 Redis 并消除并发读取竞态，确保严格单次消费
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class RedisCaptchaStore implements CaptchaStore {

    private static final String GET_AND_DELETE_LUA = """
            local val = redis.call('GET', KEYS[1])
            if val then
                redis.call('DEL', KEYS[1])
            end
            return val
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> getAndDeleteScript;

    /**
     * 构造基于 StringRedisTemplate 的验证码存储
     *
     * @param redisTemplate Spring Redis 模板
     */
    public RedisCaptchaStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.getAndDeleteScript = new DefaultRedisScript<>();
        this.getAndDeleteScript.setScriptText(GET_AND_DELETE_LUA);
        this.getAndDeleteScript.setResultType(String.class);
    }

    @Override
    public void save(String key, String answer, Duration ttl) {
        Assert.hasText(key, "captcha key must not be blank");
        Assert.hasText(answer, "captcha answer must not be blank");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("captcha ttl must be positive");
        }
        try {
            redisTemplate.opsForValue().set(key, answer, ttl);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis captcha save failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String getAndDelete(String key) {
        Assert.hasText(key, "captcha key must not be blank");
        try {
            return redisTemplate.execute(getAndDeleteScript, Collections.singletonList(key));
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis captcha getAndDelete failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(String key) {
        Assert.hasText(key, "captcha key must not be blank");
        try {
            redisTemplate.delete(key);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis captcha delete failed: " + ex.getMessage(), ex);
        }
    }
}
