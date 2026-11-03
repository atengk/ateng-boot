package io.github.atengk.boot.ai.memory.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis 的分布式会话记忆存储实现
 * <p>
 * 具备以下生产级特性：
 * 1. 纯净序列化：使用原生纯净 JSON 格式存储消息列表，彻底杜绝 Jackson {@code @class} 污染与反序列化安全风险
 * 2. 分布式漫游：支持多节点水平扩展时会话历史的统一共享
 * 3. 原生 TTL 自动淘汰：挂载 Redis 原生超时机制，自动过期清理非活跃会话，防御内存无界增长
 * 4. 空安全与保底返回：不存在或反序列化异常时保底返回不可变空集合，严禁返回 null
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * 构造 Redis 会话存储器
     *
     * @param stringRedisTemplate Spring Redis 字符串操作模版
     * @param keyPrefix           Redis 键前缀
     * @param ttl                 会话过期时长
     */
    public RedisChatMemoryStore(StringRedisTemplate stringRedisTemplate, String keyPrefix, Duration ttl) {
        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate 不能为空");
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix : "ai:chat:memory:";
        this.ttl = ttl != null ? ttl : Duration.ofDays(7);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return Collections.emptyList();
        }
        String key = formatKey(memoryId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return Collections.emptyList();
            }
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.warn("[AI-REDIS-MEMORY] 读取或反序列化 Redis 会话记忆失败 [key={}]: {}", key, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }
        String key = formatKey(memoryId);
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                stringRedisTemplate.opsForValue().set(key, json, ttl);
            } else {
                stringRedisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            log.warn("[AI-REDIS-MEMORY] 写入 Redis 会话记忆失败 [key={}]: {}", key, e.getMessage(), e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        String key = formatKey(memoryId);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[AI-REDIS-MEMORY] 删除 Redis 会话记忆失败 [key={}]: {}", key, e.getMessage(), e);
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
            log.warn("[AI-REDIS-MEMORY] 探测 Redis 会话记忆存在性失败 [key={}]: {}", key, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public int getMessageCount(Object memoryId) {
        return getMessages(memoryId).size();
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
