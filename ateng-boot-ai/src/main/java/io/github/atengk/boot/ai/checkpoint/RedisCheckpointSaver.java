package io.github.atengk.boot.ai.checkpoint;

import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import io.github.atengk.boot.core.util.JsonUtil;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 Redis 的分布式认知图检查点存储器实现类
 * <p>
 * 基于 {@link StringRedisTemplate} 与 Core 底座纯净 {@link JsonUtil} 序列化存储检查点快照，
 * 支持多 Pod 集群共享漫游与基于 TTL 的自动淘汰清理，彻底杜绝 Java 原生反序列化隐患与存储无限膨胀。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class RedisCheckpointSaver implements GraphCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointSaver.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, AtengAiGraphProperties.CheckpointProperties properties) {
        this(redisTemplate,
                properties != null ? properties.getKeyPrefix() : "ateng:ai:graph:checkpoint:",
                properties != null && properties.getCheckpointTtl() != null ? properties.getCheckpointTtl() : Duration.ofDays(7));
    }

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, String keyPrefix, Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        String threadId = resolveThreadId(config);
        String hKey = historyKey(threadId);

        List<String> ids = redisTemplate.opsForList().range(hKey, 0, -1);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> keys = ids.stream().map(id -> dataKey(threadId, id)).toList();
        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Checkpoint> result = new ArrayList<>(jsonList.size());
        for (int i = 0; i < jsonList.size(); i++) {
            String json = jsonList.get(i);
            if (json != null && !json.isBlank()) {
                String id = (i < ids.size()) ? ids.get(i) : "unknown";
                try {
                    CheckpointDto dto = JsonUtil.parseObject(json, CheckpointDto.class);
                    if (dto != null) {
                        result.add(dto.toCheckpoint());
                    }
                } catch (Exception e) {
                    log.warn("反序列化 Redis 检查点数据失败 [threadId={}, id={}]: {}", threadId, id, e.getMessage(), e);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        String threadId = resolveThreadId(config);

        String targetId;
        if (config.checkPointId().isPresent()) {
            targetId = config.checkPointId().get();
        } else {
            String hKey = historyKey(threadId);
            targetId = redisTemplate.opsForList().index(hKey, 0);
        }

        if (targetId == null || targetId.isBlank()) {
            return Optional.empty();
        }

        String json = redisTemplate.opsForValue().get(dataKey(threadId, targetId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            CheckpointDto dto = JsonUtil.parseObject(json, CheckpointDto.class);
            return Optional.ofNullable(dto != null ? dto.toCheckpoint() : null);
        } catch (Exception e) {
            log.warn("反序列化 Redis 检查点数据失败 [threadId={}, id={}]: {}", threadId, targetId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        Objects.requireNonNull(checkpoint, "Checkpoint must not be null");

        String threadId = resolveThreadId(config);
        CheckpointDto dto = CheckpointDto.from(checkpoint);
        String json = JsonUtil.toJsonString(dto);

        String dKey = dataKey(threadId, checkpoint.getId());
        redisTemplate.opsForValue().set(dKey, json, ttl);

        if (config.checkPointId().isPresent()) {
            return config;
        }

        String hKey = historyKey(threadId);
        redisTemplate.opsForList().leftPush(hKey, checkpoint.getId());
        redisTemplate.expire(hKey, ttl);

        return RunnableConfig.builder(config)
                .checkPointId(checkpoint.getId())
                .build();
    }

    @Override
    public boolean exists(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        Boolean hasKey = redisTemplate.hasKey(historyKey(threadId));
        return Boolean.TRUE.equals(hasKey);
    }

    @Override
    public void clear(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        String hKey = historyKey(threadId);
        List<String> ids = redisTemplate.opsForList().range(hKey, 0, -1);
        if (ids != null && !ids.isEmpty()) {
            List<String> dataKeys = ids.stream().map(id -> dataKey(threadId, id)).toList();
            redisTemplate.delete(dataKeys);
        }
        redisTemplate.delete(hKey);
        log.debug("已清空线程会话 [{}] 的 Redis 检查点快照与历史索引", threadId);
    }

    private String resolveThreadId(RunnableConfig config) {
        return config.threadId().orElse("default");
    }

    private String historyKey(String threadId) {
        return keyPrefix + "history:" + threadId;
    }

    private String dataKey(String threadId, String checkpointId) {
        return keyPrefix + "data:" + threadId + ":" + checkpointId;
    }
}
