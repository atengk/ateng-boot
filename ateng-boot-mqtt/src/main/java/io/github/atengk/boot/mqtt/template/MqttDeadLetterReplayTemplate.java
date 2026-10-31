package io.github.atengk.boot.mqtt.template;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * MQTT 死信受控治理与防二次雪崩重放门面
 * 提供死信主题只读探查 (peek)、受控条件重放 (replay)、清空死信 (purge) 及防二次雪崩熔断防线
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttDeadLetterReplayTemplate {

    private static final Logger log = LoggerFactory.getLogger(MqttDeadLetterReplayTemplate.class);

    /**
     * 单次默认重放/采样数量上限
     */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * 单次最大允许重放/采样数量硬上限
     */
    public static final int MAX_LIMIT = 1000;

    private final NamedMqttClientRegistry clientRegistry;
    private final MqttTemplate mqttTemplate;
    private final AtomicInteger maxReplayCount;

    public MqttDeadLetterReplayTemplate(NamedMqttClientRegistry clientRegistry, MqttTemplate mqttTemplate) {
        this(clientRegistry, mqttTemplate, 1);
    }

    public MqttDeadLetterReplayTemplate(NamedMqttClientRegistry clientRegistry, MqttTemplate mqttTemplate, int maxReplayCount) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "NamedMqttClientRegistry must not be null");
        this.mqttTemplate = Objects.requireNonNull(mqttTemplate, "MqttTemplate must not be null");
        this.maxReplayCount = new AtomicInteger(Math.max(1, maxReplayCount));
    }

    public int getMaxReplayCount() {
        return maxReplayCount.get();
    }

    public void setMaxReplayCount(int maxReplayCount) {
        this.maxReplayCount.set(Math.max(1, maxReplayCount));
    }

    // ==================== 死信只读探查接口 (peek) ====================

    /**
     * 只读探查指定死信主题的报文列表（默认客户端，采样上限 100 条，超时 2 秒）
     * 仅探查死信报文内容与异常元数据，不触发任何重放或投递
     *
     * @param dltTopic 死信主题
     * @return 死信记录列表
     */
    public List<MqttDeadLetterRecord> peek(String dltTopic) {
        return peek(null, dltTopic, DEFAULT_LIMIT, Duration.ofSeconds(2));
    }

    /**
     * 只读探查指定死信主题的报文列表（指定采样上限）
     *
     * @param dltTopic 死信主题
     * @param limit    采样报文上限
     * @return 死信记录列表
     */
    public List<MqttDeadLetterRecord> peek(String dltTopic, int limit) {
        return peek(null, dltTopic, limit, Duration.ofSeconds(2));
    }

    /**
     * 只读探查指定死信主题的报文列表（指定采样上限与等待超时）
     *
     * @param dltTopic 死信主题
     * @param limit    采样报文上限
     * @param timeout  等待超时阈值
     * @return 死信记录列表
     */
    public List<MqttDeadLetterRecord> peek(String dltTopic, int limit, Duration timeout) {
        return peek(null, dltTopic, limit, timeout);
    }

    /**
     * 指定客户端只读探查死信主题报文列表
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     * @param limit      采样报文上限
     * @param timeout    等待超时阈值
     * @return 死信记录列表
     */
    public List<MqttDeadLetterRecord> peek(String clientName, String dltTopic, int limit, Duration timeout) {
        Assert.hasText(dltTopic, "dltTopic must not be blank");
        Assert.isTrue(limit > 0, "limit must be greater than 0");
        if (limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must not exceed maximum threshold of " + MAX_LIMIT + ": " + limit);
        }
        Duration effectiveTimeout = (timeout != null && !timeout.isNegative() && !timeout.isZero()) ? timeout : Duration.ofSeconds(2);

        String effectiveClient = resolveEffectiveClientName(clientName);
        MqttClientHandle handle = clientRegistry.getClientHandle(effectiveClient);

        List<MqttDeadLetterRecord> records = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(limit);

        IMqttMessageListener listener = (topic, message) -> {
            MqttDeadLetterRecord record = MqttDeadLetterRecord.fromMqttMessage(dltTopic, message);
            records.add(record);
            latch.countDown();
        };

        try {
            handle.subscribe(dltTopic, 1, listener);
            latch.await(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[MQTT-DLT] Peek interrupted on topic [{}] for client [{}]", dltTopic, effectiveClient);
        } catch (Exception e) {
            throw new BusinessException("Failed to peek dead-letter messages on topic [" + dltTopic + "]: " + e.getMessage(), e);
        } finally {
            try {
                handle.unsubscribe(dltTopic);
            } catch (Exception e) {
                log.debug("[MQTT-DLT] Error unsubscribing after peek on [{}]: {}", dltTopic, e.getMessage());
            }
        }

        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    // ==================== 死信受控重放接口 (replay) ====================

    /**
     * 受控重放死信报文回其原始业务主题（默认客户端，采样上限 100 条）
     *
     * @param dltTopic 死信主题
     * @return 实际重放成功的报文数量
     */
    public int replay(String dltTopic) {
        return replay(dltTopic, null, DEFAULT_LIMIT, null, Duration.ofSeconds(2));
    }

    /**
     * 受控重放死信报文回其原始业务主题（指定数量上限）
     *
     * @param dltTopic 死信主题
     * @param limit    重放数量上限
     * @return 实际重放成功的报文数量
     */
    public int replay(String dltTopic, int limit) {
        return replay(dltTopic, null, limit, null, Duration.ofSeconds(2));
    }

    /**
     * 受控重放死信报文至指定目标主题（若 targetTopic 为空则回放至原始业务主题）
     *
     * @param dltTopic    死信主题
     * @param targetTopic 目标重放主题（为空则自动使用 record.originalTopic）
     * @param limit       重放数量上限
     * @return 实际重放成功的报文数量
     */
    public int replay(String dltTopic, String targetTopic, int limit) {
        return replay(dltTopic, targetTopic, limit, null, Duration.ofSeconds(2));
    }

    /**
     * 受控条件过滤重放死信报文至指定目标主题
     *
     * @param dltTopic    死信主题
     * @param targetTopic 目标重放主题
     * @param limit       重放数量上限
     * @param filter      条件过滤器（仅重放满足条件的记录）
     * @param timeout     探查超时
     * @return 实际重放成功的报文数量
     */
    public int replay(String dltTopic, String targetTopic, int limit, Predicate<MqttDeadLetterRecord> filter, Duration timeout) {
        return replayToClient(null, dltTopic, targetTopic, limit, filter, timeout);
    }

    /**
     * 指定客户端受控条件过滤重放死信报文
     *
     * @param clientName  客户端逻辑名称
     * @param dltTopic    死信主题
     * @param targetTopic 目标重放主题
     * @param limit       重放数量上限
     * @param filter      条件过滤器
     * @param timeout     探查超时
     * @return 实际重放成功的报文数量
     */
    public int replayToClient(String clientName, String dltTopic, String targetTopic, int limit,
                              Predicate<MqttDeadLetterRecord> filter, Duration timeout) {
        List<MqttDeadLetterRecord> candidates = peek(clientName, dltTopic, limit, timeout);
        if (candidates.isEmpty()) {
            log.info("[MQTT-DLT] No dead-letter messages found on topic [{}] to replay", dltTopic);
            return 0;
        }

        int replayedCount = 0;
        for (MqttDeadLetterRecord record : candidates) {
            if (filter != null && !filter.test(record)) {
                log.debug("[MQTT-DLT] Skipped replay for message on [{}] due to predicate filter", dltTopic);
                continue;
            }
            boolean success = replayRecord(clientName, record, targetTopic);
            if (success) {
                replayedCount++;
            }
        }

        log.info("[MQTT-DLT] Successfully replayed [{}/{}] dead-letter message(s) from [{}]",
                replayedCount, candidates.size(), dltTopic);
        return replayedCount;
    }

    /**
     * 单条死信记录精确重放
     * 清洗原始异常元数据标头，注入重放追踪标头，并执行防二次雪崩熔断防卫
     *
     * @param record      死信记录
     * @param targetTopic 目标重放主题（为空则自动使用 record.originalTopic）
     * @return true 若重放成功，false 若被防二次雪崩断路器熔断拦截或无可用主题
     */
    public boolean replayRecord(MqttDeadLetterRecord record, String targetTopic) {
        return replayRecord(null, record, targetTopic);
    }

    /**
     * 指定客户端对单条死信记录精确重放
     *
     * @param clientName  客户端逻辑名称
     * @param record      死信记录
     * @param targetTopic 目标重放主题
     * @return true 若重放成功，false 若被熔断拦截
     */
    public boolean replayRecord(String clientName, MqttDeadLetterRecord record, String targetTopic) {
        if (record == null) {
            return false;
        }

        // 1. 防二次雪崩熔断防线 (Secondary Avalanche Circuit Breaker)
        int currentMax = maxReplayCount.get();
        if (record.replayCount() >= currentMax) {
            log.warn("[MQTT-DLT] Dropped message replay from DLT [{}], replayCount [{}] reached maxReplayCount threshold [{}], breaking secondary avalanche loop",
                    record.dltTopic(), record.replayCount(), currentMax);
            return false;
        }

        // 2. 解析生效的目标主题
        String effectiveTarget = StringUtils.hasText(targetTopic) ? targetTopic : record.originalTopic();
        if (!StringUtils.hasText(effectiveTarget)) {
            log.error("[MQTT-DLT] Cannot replay dead-letter record without valid targetTopic or originalTopic on DLT [{}]", record.dltTopic());
            return false;
        }

        // 3. 标头清洗与全链路重放元数据注入 (Header Cleaning & Tracing)
        Map<String, String> cleanProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : record.userProperties().entrySet()) {
            String key = entry.getKey();
            if (MqttDeadLetterRecord.HEADER_ORIGINAL_TOPIC.equalsIgnoreCase(key)
                    || MqttDeadLetterRecord.HEADER_EXCEPTION_CLASS.equalsIgnoreCase(key)
                    || MqttDeadLetterRecord.HEADER_EXCEPTION_MESSAGE.equalsIgnoreCase(key)
                    || MqttDeadLetterRecord.HEADER_DLT_TIMESTAMP.equalsIgnoreCase(key)) {
                // 剔除旧的死信错误标头
                continue;
            }
            cleanProps.put(key, entry.getValue());
        }

        // 注入全新的重放治理追踪标头
        cleanProps.put(MqttDeadLetterRecord.HEADER_REPLAYED, "true");
        cleanProps.put(MqttDeadLetterRecord.HEADER_REPLAY_COUNT, String.valueOf(record.replayCount() + 1));
        cleanProps.put(MqttDeadLetterRecord.HEADER_REPLAY_TIMESTAMP, String.valueOf(System.currentTimeMillis()));

        // 4. 发起受控重放发信
        String effectiveClient = resolveEffectiveClientName(clientName);
        mqttTemplate.publishToClient(effectiveClient, effectiveTarget, record.payload(), record.qos(), false, cleanProps);
        log.info("[MQTT-DLT] Replayed dead-letter message to topic [{}] with replayCount [{}]",
                effectiveTarget, record.replayCount() + 1);
        return true;
    }

    // ==================== 死信清空抹除接口 (purge) ====================

    /**
     * 清空指定死信主题的死信报文（默认客户端，对称清理 Broker 保留消息）
     *
     * @param dltTopic 死信主题
     */
    public void purge(String dltTopic) {
        purge(null, dltTopic);
    }

    /**
     * 指定客户端清空死信主题
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     */
    public void purge(String clientName, String dltTopic) {
        Assert.hasText(dltTopic, "dltTopic must not be blank");
        String effectiveClient = resolveEffectiveClientName(clientName);

        // 对称抹除 Broker 上的保留死信 (byte[0] + retained=true)
        mqttTemplate.clearRetainedToClient(effectiveClient, dltTopic, 1);
        log.info("[MQTT-DLT] Successfully purged dead letter topic [{}] on client [{}]", dltTopic, effectiveClient);
    }

    /**
     * 指定客户端清空死信主题（兼容超时入参）
     *
     * @param clientName 客户端逻辑名称
     * @param dltTopic   死信主题
     * @param timeout    排空等待超时（保留消息清除为即时原子操作）
     */
    public void purge(String clientName, String dltTopic, Duration timeout) {
        purge(clientName, dltTopic);
    }

    private String resolveEffectiveClientName(String clientName) {
        if (StringUtils.hasText(clientName)) {
            return clientName;
        }
        return clientRegistry.getDefaultClientName();
    }
}
