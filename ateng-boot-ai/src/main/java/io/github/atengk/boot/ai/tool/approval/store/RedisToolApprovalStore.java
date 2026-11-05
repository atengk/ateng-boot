package io.github.atengk.boot.ai.tool.approval.store;

import io.github.atengk.boot.ai.properties.ToolApprovalProperties;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalTicket;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 分布式 Redis 高危工具审批工单存储实现类
 * <p>
 * 基于 {@link StringRedisTemplate} 与纯净 JSON 序列化暂存工单，通过 Redis 原生 TTL 自动淘汰过期数据，
 * 采用 ZSet 有序集合索引管理活跃 PENDING 状态工单，杜绝全库 keys 扫描引发的性能阻塞。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class RedisToolApprovalStore implements ToolApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(RedisToolApprovalStore.class);

    private final String keyPrefix;
    private final String pendingZSetKey;
    private final StringRedisTemplate redisTemplate;

    public RedisToolApprovalStore(ToolApprovalProperties properties, StringRedisTemplate redisTemplate) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        String prefix = StringUtils.hasText(properties.getKeyPrefix()) ? properties.getKeyPrefix().trim() : "ateng:ai:tool-approval:";
        if (!prefix.endsWith(":")) {
            prefix += ":";
        }
        this.keyPrefix = prefix;
        this.pendingZSetKey = prefix + "pending";
    }

    private String getTicketKey(String ticketId) {
        return keyPrefix + "ticket:" + ticketId;
    }

    @Override
    public void save(ToolApprovalTicket ticket, Duration ttl) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        String ticketId = ticket.getTicketId();
        if (!StringUtils.hasText(ticketId)) {
            throw new IllegalArgumentException("ticketId must not be empty");
        }
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : Duration.ofMinutes(30);
        String ticketKey = getTicketKey(ticketId);
        String json = JsonUtil.toJsonString(ticket);

        redisTemplate.opsForValue().set(ticketKey, json, effectiveTtl);
        if (ticket.getStatus() == ToolApprovalStatus.PENDING) {
            long expireEpochMilli = System.currentTimeMillis() + effectiveTtl.toMillis();
            redisTemplate.opsForZSet().add(pendingZSetKey, ticketId, (double) expireEpochMilli);
        }
    }

    @Override
    public Optional<ToolApprovalTicket> findById(String ticketId) {
        if (!StringUtils.hasText(ticketId)) {
            return Optional.empty();
        }
        String ticketKey = getTicketKey(ticketId);
        String json = redisTemplate.opsForValue().get(ticketKey);
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        ToolApprovalTicket ticket = JsonUtil.parseObject(json, ToolApprovalTicket.class);
        if (ticket == null) {
            return Optional.empty();
        }
        if (ticket.getStatus() == ToolApprovalStatus.PENDING && ticket.getExpireTime() != null) {
            if (LocalDateTime.now().isAfter(ticket.getExpireTime())) {
                ticket.setStatus(ToolApprovalStatus.EXPIRED);
                redisTemplate.opsForZSet().remove(pendingZSetKey, ticketId);
            }
        }
        return Optional.of(ticket);
    }

    @Override
    public boolean updateStatus(String ticketId,
                                ToolApprovalStatus expectedStatus,
                                ToolApprovalStatus newStatus,
                                String comment,
                                String executionResultJson,
                                String errorMessage) {
        if (!StringUtils.hasText(ticketId)) {
            return false;
        }
        String ticketKey = getTicketKey(ticketId);
        String json = redisTemplate.opsForValue().get(ticketKey);
        if (!StringUtils.hasText(json)) {
            return false;
        }
        ToolApprovalTicket ticket = JsonUtil.parseObject(json, ToolApprovalTicket.class);
        if (ticket == null || ticket.getStatus() != expectedStatus) {
            return false;
        }

        ticket.setStatus(newStatus);
        ticket.setReviewComment(comment);
        ticket.setReviewTime(LocalDateTime.now());
        ticket.setExecutionResultJson(executionResultJson);
        ticket.setErrorMessage(errorMessage);

        Long expireSeconds = redisTemplate.getExpire(ticketKey, TimeUnit.SECONDS);
        Duration remainingTtl = (expireSeconds != null && expireSeconds > 0)
                ? Duration.ofSeconds(expireSeconds)
                : Duration.ofMinutes(30);

        redisTemplate.opsForValue().set(ticketKey, JsonUtil.toJsonString(ticket), remainingTtl);
        redisTemplate.opsForZSet().remove(pendingZSetKey, ticketId);
        return true;
    }

    @Override
    public boolean delete(String ticketId) {
        if (!StringUtils.hasText(ticketId)) {
            return false;
        }
        String ticketKey = getTicketKey(ticketId);
        redisTemplate.opsForZSet().remove(pendingZSetKey, ticketId);
        Boolean deleted = redisTemplate.delete(ticketKey);
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public List<ToolApprovalTicket> listPending() {
        // 1. 移除已超时的 pending 记录
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(pendingZSetKey, 0, now);

        // 2. 获取有效 pending 列表
        Set<String> activeIds = redisTemplate.opsForZSet().rangeByScore(pendingZSetKey, now, Double.POSITIVE_INFINITY);
        if (activeIds == null || activeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolApprovalTicket> list = new ArrayList<>();
        for (String id : activeIds) {
            findById(id).ifPresent(ticket -> {
                if (ticket.getStatus() == ToolApprovalStatus.PENDING) {
                    list.add(ticket);
                }
            });
        }
        return Collections.unmodifiableList(list);
    }
}
