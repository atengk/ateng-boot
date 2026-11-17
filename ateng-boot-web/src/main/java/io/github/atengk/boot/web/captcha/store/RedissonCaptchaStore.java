package io.github.atengk.boot.web.captcha.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 {@link RedissonClient} 的分布式图形验证码存储实现
 * 依托 {@link RBucket#getAndDelete()} 原生原子取删能力保障集群并发单次消费
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class RedissonCaptchaStore implements CaptchaStore {

    private final RedissonClient redissonClient;

    /**
     * 构造基于 RedissonClient 的验证码存储
     *
     * @param redissonClient Redisson 客户端
     */
    public RedissonCaptchaStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
    }

    @Override
    public void save(String key, String answer, Duration ttl) {
        Assert.hasText(key, "captcha key must not be blank");
        Assert.hasText(answer, "captcha answer must not be blank");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("captcha ttl must be positive");
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(answer, ttl);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson captcha save failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String getAndDelete(String key) {
        Assert.hasText(key, "captcha key must not be blank");
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.getAndDelete();
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson captcha getAndDelete failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(String key) {
        Assert.hasText(key, "captcha key must not be blank");
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.delete();
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redisson captcha delete failed: " + ex.getMessage(), ex);
        }
    }
}
