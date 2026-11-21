package io.github.atengk.boot.sms.delay;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.properties.SmsDelayProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Redis ZSet + Lua 原子认领的分布式持久化延迟与定时短信调度器
 * 支持服务重启持久化恢复、跨节点不重投认领、可取消撤销、MDC/四维与渠道上下文恢复及 Redis 故障健壮降级
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class RedisSmsDelayScheduler extends AbstractSmsDelayScheduler {

    private static final String ZSET_SUFFIX = "zset";
    private static final String TASK_KEY_SUFFIX = "task:";

    /**
     * 原子认领到期任务：扫描 score <= now 的任务 ID，成功 ZREM 抢占后读取并删除任务负载 JSON
     */
    private static final DefaultRedisScript<List> CLAIM_DUE_LUA;

    /**
     * 原子撤销未触发任务：同时清理 ZSet 索引与任务负载键
     */
    private static final DefaultRedisScript<Long> CANCEL_LUA;

    static {
        CLAIM_DUE_LUA = new DefaultRedisScript<>();
        CLAIM_DUE_LUA.setResultType(List.class);
        CLAIM_DUE_LUA.setScriptText("""
                local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
                local payloads = {}
                for _, id in ipairs(ids) do
                    if redis.call('ZREM', KEYS[1], id) == 1 then
                        local taskKey = ARGV[3] .. id
                        local data = redis.call('GET', taskKey)
                        if data then
                            redis.call('DEL', taskKey)
                            table.insert(payloads, data)
                        end
                    end
                end
                return payloads
                """);

        CANCEL_LUA = new DefaultRedisScript<>();
        CANCEL_LUA.setResultType(Long.class);
        CANCEL_LUA.setScriptText("""
                local removed = redis.call('ZREM', KEYS[1], ARGV[1])
                local taskKey = ARGV[2] .. ARGV[1]
                local deleted = redis.call('DEL', taskKey)
                if removed > 0 or deleted > 0 then
                    return 1
                end
                return 0
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final ReentrantLock pollLock = new ReentrantLock();
    private final ThreadPoolTaskScheduler pollerScheduler;

    public RedisSmsDelayScheduler(StringRedisTemplate redisTemplate, SmsDelayProperties properties) {
        this(redisTemplate, properties, Clock.systemDefaultZone(), true);
    }

    public RedisSmsDelayScheduler(StringRedisTemplate redisTemplate,
                                  SmsDelayProperties properties,
                                  Clock clock,
                                  boolean startPoller) {
        super(properties, clock);
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate 不能为空");
        if (startPoller) {
            this.pollerScheduler = createInitializedScheduler("ateng-sms-redis-delay-", this.properties);
            Duration interval = this.properties.getRedisPollInterval() != null
                    && !this.properties.getRedisPollInterval().isNegative()
                    && !this.properties.getRedisPollInterval().isZero()
                    ? this.properties.getRedisPollInterval()
                    : Duration.ofSeconds(1);
            this.pollerScheduler.scheduleWithFixedDelay(this::pollAndDispatchDueTasks, interval);
        } else {
            this.pollerScheduler = null;
        }
    }

    @Override
    public SmsScheduledReceipt scheduleSingle(SmsRequest request, Instant triggerAt, String channelHint) {
        BusinessAssert.notNull(request, "单条发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        String taskId = IdUtil.fastSimpleUUID();
        StoredTaskPayload payload = StoredTaskPayload.fromSingle(taskId, request, triggerAt.toEpochMilli());
        persistTask(taskId, payload, triggerAt);
        return new SmsScheduledReceipt(taskId, triggerAt, channelHint, false, request.getPhones().size(), this);
    }

    @Override
    public SmsScheduledReceipt scheduleBatch(BatchSmsRequest request, Instant triggerAt, String channelHint) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        String taskId = IdUtil.fastSimpleUUID();
        StoredTaskPayload payload = StoredTaskPayload.fromBatch(taskId, request, triggerAt.toEpochMilli());
        persistTask(taskId, payload, triggerAt);
        return new SmsScheduledReceipt(taskId, triggerAt, channelHint, true, request.getPhones().size(), this);
    }

    private void persistTask(String taskId, StoredTaskPayload payload, Instant triggerAt) {
        try {
            String json = JsonUtil.toJsonString(payload);
            redisTemplate.opsForValue().set(taskKey(taskId), json);
            Boolean added = redisTemplate.opsForZSet().add(zsetKey(), taskId, (double) triggerAt.toEpochMilli());
            if (!Boolean.TRUE.equals(added)) {
                log.warn("[RedisSmsDelayScheduler] 任务 [{}] ZSet 写入未确认新增 (可能发生重复提交)", taskId);
            }
            recordStatus(taskId, SmsScheduledStatus.PENDING);
        } catch (Exception ex) {
            try {
                redisTemplate.delete(taskKey(taskId));
            } catch (Exception cleanupEx) {
                log.debug("[RedisSmsDelayScheduler] 任务 [{}] ZSet 写入失败后补偿清理负载键失败: {}", taskId, cleanupEx.getMessage());
            }
            throw new SmsSendException(payload.getChannel(), "REDIS_DELAY_ENQUEUE_ERROR", ex.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, "写入 Redis 延迟短信调度队列失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean cancel(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        String key = taskId.trim();
        try {
            Long removed = redisTemplate.execute(
                    CANCEL_LUA,
                    Collections.singletonList(zsetKey()),
                    key,
                    taskKeyPrefix()
            );
            boolean success = removed != null && removed > 0;
            if (success) {
                recordStatus(key, SmsScheduledStatus.CANCELLED);
                if (log.isDebugEnabled()) {
                    log.debug("[RedisSmsDelayScheduler] 已撤销 Redis 待触发延迟短信任务 [{}]", key);
                }
            }
            return success;
        } catch (Exception ex) {
            log.warn("[RedisSmsDelayScheduler] 撤销 Redis 延迟任务 [{}] 异常: {}", key, ex.getMessage());
            return false;
        }
    }

    /**
     * 轮询并原子认领当前时钟下的全部到期任务（支持单测确定性主动触发与后台周期调用双复用）
     *
     * @return 本轮认领并分发执行的任务数
     */
    @SuppressWarnings("unchecked")
    public int pollAndDispatchDueTasks() {
        if (!pollLock.tryLock()) {
            return 0;
        }
        try {
            long nowMillis = clock.millis();
            int batchSize = properties.getRedisBatchSize() > 0 ? properties.getRedisBatchSize() : 100;
            List<Object> rawPayloads = redisTemplate.execute(
                    CLAIM_DUE_LUA,
                    Collections.singletonList(zsetKey()),
                    String.valueOf(nowMillis),
                    String.valueOf(batchSize),
                    taskKeyPrefix()
            );
            if (rawPayloads == null || rawPayloads.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (Object raw : rawPayloads) {
                if (raw != null) {
                    dispatchSerializedPayload(String.valueOf(raw));
                    count++;
                }
            }
            return count;
        } catch (Exception ex) {
            log.warn("[RedisSmsDelayScheduler] 轮询 Redis 延迟短信队列异常，等待下轮恢复: {}", ex.getMessage());
            return 0;
        } finally {
            if (pollLock.isHeldByCurrentThread()) {
                pollLock.unlock();
            }
        }
    }

    private void dispatchSerializedPayload(String json) {
        StoredTaskPayload payload;
        try {
            payload = JsonUtil.parseObject(json, StoredTaskPayload.class);
        } catch (Exception ex) {
            log.error("[RedisSmsDelayScheduler] 反序列化延迟短信任务负载失败: {}", ex.getMessage());
            return;
        }
        if (payload == null || !StringUtils.hasText(payload.getTaskId())) {
            return;
        }

        String taskId = payload.getTaskId();
        AsyncContext.ContextSnapshot previous = AsyncContext.capturePrevious();
        try {
            applyCapturedContext(payload);
            SmsScheduledStatus status = payload.isBatch()
                    ? executeBatchDispatch(taskId, payload.toBatchSmsRequest())
                    : executeSingleDispatch(taskId, payload.toSmsRequest());
            recordStatus(taskId, status);
        } finally {
            previous.restore();
        }
    }

    private void applyCapturedContext(StoredTaskPayload payload) {
        if (payload.getMdc() != null && !payload.getMdc().isEmpty()) {
            MDC.setContextMap(payload.getMdc());
        } else {
            MDC.clear();
        }
        if (StringUtils.hasText(payload.getUserId())) {
            UserContextHolder.set(payload.getUserId());
        } else {
            UserContextHolder.set((String) null);
        }
        if (StringUtils.hasText(payload.getTenantId())) {
            TenantContextHolder.setTenantId(payload.getTenantId());
        } else {
            TenantContextHolder.setTenantId((String) null);
        }
        TenantContextHolder.setIgnoreTenant(payload.isIgnoreTenant());
        if (StringUtils.hasText(payload.getContextChannel())) {
            SmsContextHolder.setChannel(payload.getContextChannel());
        } else {
            SmsContextHolder.clear();
        }
    }

    private String keyPrefix() {
        String prefix = properties.getRedisKeyPrefix();
        if (!StringUtils.hasText(prefix)) {
            return "ateng:sms:delay:";
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    private String zsetKey() {
        return keyPrefix() + ZSET_SUFFIX;
    }

    private String taskKeyPrefix() {
        return keyPrefix() + TASK_KEY_SUFFIX;
    }

    private String taskKey(String taskId) {
        return taskKeyPrefix() + taskId;
    }

    @Override
    public void destroy() {
        if (pollerScheduler != null) {
            pollerScheduler.shutdown();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ParamData {
        private Map<String, Object> mapParams;
        private List<String> arrayParams;
        private boolean arrayMode;

        static ParamData from(SmsParam param) {
            if (param == null) {
                return new ParamData(Collections.emptyMap(), Collections.emptyList(), false);
            }
            return new ParamData(
                    new LinkedHashMap<>(param.getMapParams()),
                    new ArrayList<>(param.getArrayParams()),
                    param.isArrayMode()
            );
        }

        SmsParam toSmsParam() {
            if (arrayMode) {
                return SmsParam.ofArray(arrayParams != null ? arrayParams : Collections.emptyList());
            }
            return SmsParam.ofMap(mapParams);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StoredTaskPayload {
        private String taskId;
        private boolean batch;
        private long triggerAtMillis;
        private String phone;
        private List<String> phones;
        private String templateId;
        private String signName;
        private String channel;
        private String bizId;
        private boolean urgent;
        private String content;
        private boolean skipRateLimit;
        private ParamData params;
        private Map<String, ParamData> phoneParams;
        private String contextChannel;
        private String userId;
        private String tenantId;
        private boolean ignoreTenant;
        private Map<String, String> mdc;

        static StoredTaskPayload fromSingle(String taskId, SmsRequest req, long triggerAtMillis) {
            Map<String, String> mdcMap = MDC.getCopyOfContextMap();
            return new StoredTaskPayload(
                    taskId,
                    false,
                    triggerAtMillis,
                    req.getPhone(),
                    new ArrayList<>(req.getPhones()),
                    req.getTemplateId(),
                    req.getSignName(),
                    req.getChannel(),
                    req.getBizId(),
                    req.isUrgent(),
                    req.getContent(),
                    req.isSkipRateLimit(),
                    ParamData.from(req.getParams()),
                    Collections.emptyMap(),
                    SmsContextHolder.getChannel(),
                    UserContextHolder.getExplicit(),
                    TenantContextHolder.getExplicit(),
                    TenantContextHolder.isIgnoreTenant(),
                    mdcMap != null ? new LinkedHashMap<>(mdcMap) : Collections.emptyMap()
            );
        }

        static StoredTaskPayload fromBatch(String taskId, BatchSmsRequest req, long triggerAtMillis) {
            Map<String, String> mdcMap = MDC.getCopyOfContextMap();
            Map<String, ParamData> pMaps = new LinkedHashMap<>();
            if (req.getPhoneParams() != null) {
                req.getPhoneParams().forEach((k, v) -> pMaps.put(k, ParamData.from(v)));
            }
            return new StoredTaskPayload(
                    taskId,
                    true,
                    triggerAtMillis,
                    null,
                    new ArrayList<>(req.getPhones()),
                    req.getTemplateId(),
                    req.getSignName(),
                    req.getChannel(),
                    req.getBizId(),
                    req.isUrgent(),
                    req.getContent(),
                    false,
                    ParamData.from(req.getParams()),
                    pMaps,
                    SmsContextHolder.getChannel(),
                    UserContextHolder.getExplicit(),
                    TenantContextHolder.getExplicit(),
                    TenantContextHolder.isIgnoreTenant(),
                    mdcMap != null ? new LinkedHashMap<>(mdcMap) : Collections.emptyMap()
            );
        }

        SmsRequest toSmsRequest() {
            return new SmsRequest(
                    phone,
                    phones,
                    templateId,
                    params != null ? params.toSmsParam() : SmsParam.create(),
                    signName,
                    channel,
                    bizId,
                    urgent,
                    content,
                    skipRateLimit
            );
        }

        BatchSmsRequest toBatchSmsRequest() {
            Map<String, SmsParam> pMap = new LinkedHashMap<>();
            if (phoneParams != null) {
                phoneParams.forEach((k, v) -> pMap.put(k, v != null ? v.toSmsParam() : SmsParam.create()));
            }
            return new BatchSmsRequest(
                    phones,
                    templateId,
                    params != null ? params.toSmsParam() : SmsParam.create(),
                    pMap,
                    signName,
                    channel,
                    bizId,
                    urgent,
                    content
            );
        }
    }
}
