package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Redis 分布式原子滑动窗口限流器 (Redis Lua Atomic Sliding Window Rate Limiter)
 * <p>
 * 基于 Redis Sorted Set (ZSET) 与原子 Lua 脚本执行双维微观突发限流：
 * <ul>
 *   <li><b>RPS 维度</b>：1 秒 (1000ms) 毫秒级 ZSET 滑动窗口；</li>
 *   <li><b>TPM 维度</b>：1 分钟 (60000ms) 变长 Token 权重 ZSET 滑动窗口；</li>
 *   <li><b>原子强一致</b>：检查与扣减在单次 Redis Lua 调用中原子完成，未达上限前不占用名额；</li>
 *   <li><b>弹性高可用</b>：当 Redis 发生不可用或网络异常时，遵循 SRE 生产规范自动降级平滑放行。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RedisAiRateLimiter implements AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisAiRateLimiter.class);

    private static final String SLIDING_WINDOW_LUA = """
            local rpsKey = KEYS[1]
            local tpmKey = KEYS[2]
            local now = tonumber(ARGV[1])
            local rpsLimit = tonumber(ARGV[2])
            local tpmLimit = tonumber(ARGV[3])
            local tokens = tonumber(ARGV[4])
            local memberId = ARGV[5]

            local rpsWindow = 1000
            local tpmWindow = 60000

            -- 1. 检查 RPS 滑动窗口 (1s)
            if rpsLimit > 0 then
                redis.call('ZREMRANGEBYSCORE', rpsKey, 0, now - rpsWindow)
                local currentRps = redis.call('ZCARD', rpsKey)
                if currentRps + 1 > rpsLimit then
                    return 0
                end
            end

            -- 2. 检查 TPM 滑动窗口 (60s)
            if tpmLimit > 0 then
                redis.call('ZREMRANGEBYSCORE', tpmKey, 0, now - tpmWindow)
                local entries = redis.call('ZRANGEBYSCORE', tpmKey, now - tpmWindow, '+inf')
                local currentTokens = 0
                for _, item in ipairs(entries) do
                    local colonIdx = string.find(item, ':')
                    if colonIdx then
                        currentTokens = currentTokens + tonumber(string.sub(item, colonIdx + 1))
                    else
                        currentTokens = currentTokens + 1
                    end
                end
                if currentTokens + tokens > tpmLimit then
                    return -1
                end
            end

            -- 3. 双维通过后原子记录
            if rpsLimit > 0 then
                redis.call('ZADD', rpsKey, now, memberId)
                redis.call('PEXPIRE', rpsKey, rpsWindow * 2)
            end

            if tpmLimit > 0 then
                redis.call('ZADD', tpmKey, now, memberId .. ':' .. tostring(tokens))
                redis.call('PEXPIRE', tpmKey, tpmWindow * 2)
            end

            return 1
            """;

    private final AtengAiRateLimitProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> redisScript;

    public RedisAiRateLimiter(AtengAiRateLimitProperties properties, StringRedisTemplate redisTemplate) {
        Assert.notNull(redisTemplate, "StringRedisTemplate 不能为空");
        this.properties = properties != null ? properties : new AtengAiRateLimitProperties();
        this.redisTemplate = redisTemplate;
        this.redisScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
    }

    @Override
    public boolean tryAcquire(String tenantId, String modelName, int estimatedTokens) {
        if (!properties.isEnabled()) {
            return true;
        }

        RateLimitRuleResolver.ResolvedRule rule = RateLimitRuleResolver.resolve(properties, tenantId, modelName);
        if (!rule.hasLimit()) {
            return true;
        }

        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "ateng:ai:ratelimit:";
        String cleanTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : "global";
        String cleanModel = StringUtils.hasText(modelName) ? modelName.trim() : "default";

        String rpsKey = prefix + "rps:" + cleanTenant + ":" + cleanModel;
        String tpmKey = prefix + "tpm:" + cleanTenant + ":" + cleanModel;

        long now = System.currentTimeMillis();
        int tokenAcquire = Math.max(1, estimatedTokens);
        String memberId = IdUtil.fastSimpleUUID();

        try {
            Long result = redisTemplate.execute(
                    redisScript,
                    List.of(rpsKey, tpmKey),
                    String.valueOf(now),
                    String.valueOf(rule.rps()),
                    String.valueOf(rule.tpm()),
                    String.valueOf(tokenAcquire),
                    memberId
            );

            if (result == null || result == 1L) {
                return true;
            }

            log.warn("[AI-RATE-LIMIT-REDIS] 触发分布式微观突发流量限流 [tenant={}, model={}, reason={}]",
                    cleanTenant, cleanModel, result == 0L ? "RPS 超限" : "TPM 超限");
            return false;
        } catch (Throwable t) {
            log.warn("[AI-RATE-LIMIT-REDIS] Redis 限流执行遇到异常 ({})，自动弹性降级平滑放行", t.getMessage());
            return true;
        }
    }

    @Override
    public boolean isDistributed() {
        return true;
    }
}
