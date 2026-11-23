package io.github.atengk.boot.ai.memory.store;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.memory.SummaryMemoryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 Redis 的分布式长会话记忆双层状态存储实现
 * <p>
 * 具备以下生产级特性：
 * 1. 纯净序列化：使用原生纯净 JSON 格式存储双层状态，彻底杜绝 Jackson {@code @class} 污染与反序列化安全风险
 * 2. 分布式多节点共享：支持无状态微服务集群会话历史的漫游共享
 * 3. 滑动 TTL 自动续期：每次更新均执行滑动 TTL 续期，自动淘汰冷会话，防御内存无界膨胀
 * 4. 空安全与保底返回：不存在或反序列化异常时保底返回空状态对象，严禁返回 null
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RedisSummaryChatMemoryStore implements SummaryChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSummaryChatMemoryStore.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * 构造 Redis 长会话双层存储器
     *
     * @param stringRedisTemplate Spring Redis 字符串操作模版
     * @param keyPrefix           Redis 键前缀
     * @param ttl                 会话过期时长（滑动 TTL）
     */
    public RedisSummaryChatMemoryStore(StringRedisTemplate stringRedisTemplate, String keyPrefix, Duration ttl) {
        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate 不能为空");
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix : "ai:summary:memory:";
        this.ttl = ttl != null ? ttl : Duration.ofDays(7);
    }

    @Override
    public SummaryMemoryState getState(Object memoryId) {
        if (memoryId == null) {
            return new SummaryMemoryState();
        }
        String key = formatKey(memoryId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return new SummaryMemoryState();
            }
            return SummaryMemoryState.fromJson(json);
        } catch (Exception e) {
            log.warn("[AI-REDIS-SUMMARY-MEMORY] 读取 Redis 会话状态失败 [key={}]: {}", key, e.getMessage(), e);
            return new SummaryMemoryState();
        }
    }

    @Override
    public void updateState(Object memoryId, SummaryMemoryState state) {
        if (memoryId == null) {
            return;
        }
        String key = formatKey(memoryId);
        if (state == null || (!state.hasSummary() && state.getMessages().isEmpty())) {
            deleteState(memoryId);
            return;
        }
        try {
            String json = state.toJson();
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                stringRedisTemplate.opsForValue().set(key, json, ttl);
            } else {
                stringRedisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_MEMORY_STORE_ERROR,
                    "向 Redis 写入长会话双层记忆状态失败 [key=" + key + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteState(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        String key = formatKey(memoryId);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_MEMORY_STORE_ERROR,
                    "从 Redis 删除长会话双层记忆状态失败 [key=" + key + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        String key = formatKey(memoryId);
        try {
            Boolean hasKey = stringRedisTemplate.hasKey(key);
            return Boolean.TRUE.equals(hasKey);
        } catch (Exception e) {
            log.warn("[AI-REDIS-SUMMARY-MEMORY] 探测 Redis 会话存在性失败 [key={}]: {}", key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 根据 memoryId 格式化 Redis 完整物理键名
     *
     * @param memoryId 会话标识
     * @return Redis 键名
     */
    public String formatKey(Object memoryId) {
        Assert.notNull(memoryId, "memoryId 不能为空");
        return this.keyPrefix + memoryId;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }
}
