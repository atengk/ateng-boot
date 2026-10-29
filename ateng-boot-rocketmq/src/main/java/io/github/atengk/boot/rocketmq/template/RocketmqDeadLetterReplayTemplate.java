package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;

/**
 * RocketMQ 5.x 死信队列治理与受控重放门面
 *
 * <p>基于 {@link RocketmqSimpleConsumerTemplate} 与 {@link RocketmqTemplate} 构建，
 * 专职负责死信队列（{@code %DLQ%<ConsumerGroup>}）的只读探查（{@link #peekDeadLetters}，零 ACK）、
 * 受控重放（{@link #replayDeadLetters}）、内部重试头安全清洗、全链路审计打标以及防二次雪崩熔断防线。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class RocketmqDeadLetterReplayTemplate {

    private final RocketmqSimpleConsumerTemplate simpleConsumerTemplate;
    private final RocketmqTemplate rocketmqTemplate;
    private final AtengRocketmqProperties properties;

    public RocketmqDeadLetterReplayTemplate(
            RocketmqSimpleConsumerTemplate simpleConsumerTemplate,
            RocketmqTemplate rocketmqTemplate,
            AtengRocketmqProperties properties) {
        this.simpleConsumerTemplate = Objects.requireNonNull(simpleConsumerTemplate, "simpleConsumerTemplate 不能为空");
        this.rocketmqTemplate = Objects.requireNonNull(rocketmqTemplate, "rocketmqTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /**
     * 获取死信队列治理配置属性
     *
     * @return RocketMQ 配置属性类
     */
    public AtengRocketmqProperties getProperties() {
        return this.properties;
    }

    /**
     * 获取流式拉取消费门面
     *
     * @return SimpleConsumerTemplate 实例
     */
    public RocketmqSimpleConsumerTemplate getSimpleConsumerTemplate() {
        return this.simpleConsumerTemplate;
    }

    /**
     * 获取发送门面
     *
     * @return RocketmqTemplate 实例
     */
    public RocketmqTemplate getRocketmqTemplate() {
        return this.rocketmqTemplate;
    }

    /**
     * 根据消费组名称自动映射解析死信主题名称
     *
     * @param consumerGroup 消费组名称
     * @return 确定的死信主题名称（例如 {@code %DLQ%GID_ORDER}）
     */
    public String getDlqTopic(String consumerGroup) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        String trimmed = consumerGroup.trim();
        if (trimmed.startsWith(RocketmqConstants.DLQ_TOPIC_PREFIX)) {
            return trimmed;
        }
        return RocketmqConstants.DLQ_TOPIC_PREFIX + trimmed;
    }

    // =========================================================================
    // 1. 只读探查死信队列 (peekDeadLetters - 零 ACK)
    // =========================================================================

    /**
     * 只读探查目标消费组的死信队列（默认批量大小，默认租约时长）
     *
     * @param consumerGroup 目标消费组
     * @return 死信消息列表（若无消息返回空集合，绝不返回 null）
     */
    public List<MessageView> peekDeadLetters(String consumerGroup) {
        return peekDeadLetters(consumerGroup, properties.getDeadLetter().getDefaultBatchSize());
    }

    /**
     * 只读探查目标消费组的死信队列（指定最大拉取数量，默认租约时长）
     *
     * @param consumerGroup 目标消费组
     * @param maxMessageNum 最大拉取数量（受 maxBatchSize 上限拦截）
     * @return 死信消息列表
     */
    public List<MessageView> peekDeadLetters(String consumerGroup, int maxMessageNum) {
        return peekDeadLetters(consumerGroup, maxMessageNum, properties.getDeadLetter().getInvisibleDuration());
    }

    /**
     * 只读探查目标消费组的死信队列（指定最大拉取数量与租约时长）
     *
     * <p>仅基于 SimpleConsumer 执行拉取，绝对不执行 ACK，保证死信在不可见租约到期后自动恢复，零丢信风险。</p>
     *
     * @param consumerGroup     目标消费组
     * @param maxMessageNum     最大拉取数量（必须大于 0 且不超过 maxBatchSize）
     * @param invisibleDuration 不可见租约时长
     * @return 死信消息列表
     */
    public List<MessageView> peekDeadLetters(String consumerGroup, int maxMessageNum, Duration invisibleDuration) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        validateBatchSize(maxMessageNum);
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

        String dlqTopic = getDlqTopic(consumerGroup);
        log.info("[MQ-DLQ-PEEK] 开始只读探查死信队列: dlqTopic={}, consumerGroup={}, maxMessageNum={}, invisibleDuration={}",
                dlqTopic, consumerGroup, maxMessageNum, invisibleDuration);

        List<MessageView> messages = simpleConsumerTemplate.receive(
                dlqTopic,
                consumerGroup,
                RocketmqConstants.TAG_ALL,
                maxMessageNum,
                invisibleDuration);

        log.info("[MQ-DLQ-PEEK] 只读探查死信队列完成 (零 ACK 保留): dlqTopic={}, consumerGroup={}, 探查到 {} 条死信",
                dlqTopic, consumerGroup, messages != null ? messages.size() : 0);
        return messages != null ? messages : Collections.emptyList();
    }

    // =========================================================================
    // 2. 受控重放死信队列 (replayDeadLetters)
    // =========================================================================

    /**
     * 受控重放目标消费组的死信至原始业务主题（默认批量大小）
     *
     * @param consumerGroup 目标消费组
     * @return 重放执行汇总结果
     */
    public DeadLetterReplayResult replayDeadLetters(String consumerGroup) {
        return replayDeadLetters(consumerGroup, properties.getDeadLetter().getDefaultBatchSize());
    }

    /**
     * 受控重放目标消费组的死信至原始业务主题（指定最大拉取数量）
     *
     * @param consumerGroup 目标消费组
     * @param maxMessageNum 最大拉取数量
     * @return 重放执行汇总结果
     */
    public DeadLetterReplayResult replayDeadLetters(String consumerGroup, int maxMessageNum) {
        return replayDeadLetters(consumerGroup, maxMessageNum, (String) null);
    }

    /**
     * 受控重放目标消费组的死信至指定目标主题（默认批量大小）
     *
     * @param consumerGroup 目标消费组
     * @param targetTopic   显式指定的目标投递主题
     * @return 重放执行汇总结果
     */
    public DeadLetterReplayResult replayDeadLetters(String consumerGroup, String targetTopic) {
        return replayDeadLetters(consumerGroup, properties.getDeadLetter().getDefaultBatchSize(), targetTopic);
    }

    /**
     * 受控重放目标消费组的死信至指定目标主题（指定最大拉取数量，默认租约时长）
     *
     * @param consumerGroup 目标消费组
     * @param maxMessageNum 最大拉取数量
     * @param targetTopic   显式指定的目标投递主题（若为空则自动从死信元数据中解析）
     * @return 重放执行汇总结果
     */
    public DeadLetterReplayResult replayDeadLetters(String consumerGroup, int maxMessageNum, String targetTopic) {
        return replayDeadLetters(consumerGroup, maxMessageNum, targetTopic, properties.getDeadLetter().getInvisibleDuration());
    }

    /**
     * 受控重放目标消费组死信的核心执行方法
     *
     * <ol>
     *   <li>从死信主题 {@code %DLQ%<ConsumerGroup>} 批量拉取死信报文；</li>
     *   <li>针对每条死信消息检查累计重放次数 {@code X-Dead-Letter-Replay-Count}，达到上限强行熔断拦截防二次雪崩；</li>
     *   <li>安全清洗底层内部重试协议头，注入全链路重放审计协议头；</li>
     *   <li>投递成功后，才对原 DLQ 消息执行显式 ACK 确认，确保绝对不发生消息丢失。</li>
     * </ol>
     *
     * @param consumerGroup     目标消费组
     * @param maxMessageNum     最大拉取数量
     * @param targetTopic       显式指定的目标主题（可选）
     * @param invisibleDuration 不可见租约时长
     * @return 重放执行汇总结果
     */
    public DeadLetterReplayResult replayDeadLetters(
            String consumerGroup,
            int maxMessageNum,
            String targetTopic,
            Duration invisibleDuration) {
        Assert.hasText(consumerGroup, "consumerGroup 不能为空");
        validateBatchSize(maxMessageNum);
        Assert.notNull(invisibleDuration, "invisibleDuration 不能为空");
        Assert.isTrue(!invisibleDuration.isZero() && !invisibleDuration.isNegative(), "invisibleDuration 必须大于 0");

        if (StringUtils.hasText(targetTopic) && !RocketmqConstants.TOPIC_PATTERN.matcher(targetTopic.trim()).matches()) {
            throw new IllegalArgumentException(String.format(
                    "目标 Topic 名称 [%s] 格式非法，必须符合正则 [%s]", targetTopic, RocketmqConstants.TOPIC_REGEX));
        }

        String dlqTopic = getDlqTopic(consumerGroup);
        log.info("[MQ-DLQ-REPLAY] 开始受控重放死信队列: dlqTopic={}, consumerGroup={}, maxMessageNum={}, targetTopic={}",
                dlqTopic, consumerGroup, maxMessageNum, targetTopic != null ? targetTopic : "AUTO_RESOLVE");

        List<MessageView> dlqMessages = simpleConsumerTemplate.receive(
                dlqTopic,
                consumerGroup,
                RocketmqConstants.TAG_ALL,
                maxMessageNum,
                invisibleDuration);

        DeadLetterReplayResult result = new DeadLetterReplayResult(dlqTopic, consumerGroup);
        if (dlqMessages == null || dlqMessages.isEmpty()) {
            log.info("[MQ-DLQ-REPLAY] 死信队列当前暂无积压死信: dlqTopic={}, consumerGroup={}", dlqTopic, consumerGroup);
            return result;
        }

        int maxReplayAttempts = properties.getDeadLetter().getMaxReplayAttempts();

        for (MessageView messageView : dlqMessages) {
            String messageId = messageView.getMessageId() != null ? messageView.getMessageId().toString() : "UNKNOWN";
            Map<String, String> originalProps = messageView.getProperties() != null
                    ? messageView.getProperties()
                    : Collections.emptyMap();
            String bizKey = RocketmqMessageHelper.extractBizKey(messageView);

            // 1. 四维上下文入站还原并在 finally 严格清理
            try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(originalProps)) {

                // 2. 检查累计重放次数与防二次雪崩熔断防线
                int currentReplayCount = parseReplayCount(originalProps.get(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT));
                if (currentReplayCount >= maxReplayAttempts) {
                    log.error("[MQ-DLQ-AVALANCHE-BLOCKED] mq=rocketmq dlqTopic={} consumerGroup={} messageId={} bizKey={} currentReplayCount={} maxReplayAttempts={} 死信累计重放次数已达到或超过最大上限，防二次雪崩熔断拦截！",
                            dlqTopic, consumerGroup, messageId, bizKey, currentReplayCount, maxReplayAttempts);
                    result.recordBlocked(
                            messageId,
                            bizKey,
                            currentReplayCount,
                            String.format("死信累计重放次数已达到或超出上限 [%d]，防二次雪崩熔断拦截", maxReplayAttempts));
                    continue;
                }

                // 3. 解析目标投递主题
                String effectiveTopic = StringUtils.hasText(targetTopic)
                        ? targetTopic.trim()
                        : resolveOriginalTopic(messageView, originalProps);

                if (!StringUtils.hasText(effectiveTopic)) {
                    log.warn("[MQ-DLQ-UNRESOLVED-TOPIC] mq=rocketmq dlqTopic={} consumerGroup={} messageId={} bizKey={} 无法解析原始业务主题且未指定 targetTopic，跳过重放",
                            dlqTopic, consumerGroup, messageId, bizKey);
                    result.recordUnresolvedTopic(messageId, bizKey, currentReplayCount, "无法解析原始业务主题，且未显式指定 targetTopic");
                    continue;
                }

                // 4. 清洗底层重试协议头并注入全链路重放审计协议头
                Map<String, String> sanitizedProps = sanitizeHeaders(originalProps);
                int nextReplayCount = currentReplayCount + 1;
                sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_REPLAYED, "true");
                sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT, String.valueOf(nextReplayCount));
                sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_TIME, String.valueOf(System.currentTimeMillis()));
                sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_CONSUMER_GROUP, consumerGroup);
                sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_TOPIC, effectiveTopic);
                if (messageView.getMessageId() != null) {
                    sanitizedProps.put(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_MESSAGE_ID, messageView.getMessageId().toString());
                }

                // 5. 提取报文载荷与属性
                byte[] bodyBytes = extractAndDecompressBody(messageView, originalProps);
                String tag = messageView.getTag().orElse(null);
                String messageGroup = messageView.getMessageGroup().orElse(null);

                // 6. 重新分发至目标业务主题
                SendReceipt receipt;
                try {
                    if (StringUtils.hasText(messageGroup)) {
                        receipt = rocketmqTemplate.sendOrderly(effectiveTopic, tag, bizKey, bodyBytes, messageGroup, sanitizedProps);
                    } else {
                        receipt = rocketmqTemplate.send(effectiveTopic, tag, bizKey, bodyBytes, sanitizedProps);
                    }
                } catch (Exception ex) {
                    log.error("[MQ-DLQ-REPLAY-FAILED] mq=rocketmq dlqTopic={} targetTopic={} consumerGroup={} messageId={} bizKey={} 重新投递目标主题失败，绝不确认原死信: {}",
                            dlqTopic, effectiveTopic, consumerGroup, messageId, bizKey, ex.getMessage(), ex);
                    result.recordFailed(messageId, bizKey, effectiveTopic, nextReplayCount, "投递目标主题失败: " + ex.getMessage());
                    continue;
                }

                // 7. 投递成功后，执行确定性源端死信 ACK 确认
                String newReceiptId = (receipt != null && receipt.getMessageId() != null) ? receipt.getMessageId().toString() : "";
                try {
                    simpleConsumerTemplate.ack(consumerGroup, messageView);
                    log.info("[MQ-DLQ-REPLAY-SUCCESS] mq=rocketmq dlqTopic={} targetTopic={} consumerGroup={} messageId={} newReceiptId={} bizKey={} replayCount={} 死信受控重放成功并已确认原死信",
                            dlqTopic, effectiveTopic, consumerGroup, messageId, newReceiptId, bizKey, nextReplayCount);
                    result.recordSuccess(messageId, newReceiptId, bizKey, effectiveTopic, nextReplayCount);
                } catch (Exception ex) {
                    log.error("[MQ-DLQ-ACK-FAILED] mq=rocketmq dlqTopic={} targetTopic={} consumerGroup={} messageId={} bizKey={} 死信已成功重新投递，但源端死信确认失败: {}",
                            dlqTopic, effectiveTopic, consumerGroup, messageId, bizKey, ex.getMessage(), ex);
                    result.recordFailed(messageId, bizKey, effectiveTopic, nextReplayCount, "重新投递成功但源端死信确认失败: " + ex.getMessage());
                }
            }
        }

        log.info("[MQ-DLQ-REPLAY-FINISHED] 死信重放治理完成: dlqTopic={}, consumerGroup={}, 统计={}",
                dlqTopic, consumerGroup, result);
        return result;
    }

    /**
     * 校验批量治理数量上限
     */
    private void validateBatchSize(int batchSize) {
        Assert.isTrue(batchSize > 0, () -> String.format("死信治理批量数量 [%d] 必须大于 0", batchSize));
        int maxBatchSize = properties.getDeadLetter().getMaxBatchSize();
        Assert.isTrue(batchSize <= maxBatchSize, () -> String.format(
                "死信治理批量数量 [%d] 超出配置上限 [%d]，触发 Fail-Fast 防护",
                batchSize, maxBatchSize));
    }

    /**
     * 解析重放累计次数
     */
    private int parseReplayCount(String val) {
        if (!StringUtils.hasText(val)) {
            return 0;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 从消息元数据多级解析原始业务主题
     */
    private String resolveOriginalTopic(MessageView messageView, Map<String, String> properties) {
        if (properties != null) {
            String originTopic = properties.get(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_TOPIC);
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
            originTopic = properties.get("X-Original-Topic");
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
            originTopic = properties.get("__REAL_TOPIC");
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
            originTopic = properties.get("ORIGIN_TOPIC");
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
            originTopic = properties.get("DLQ_ORIGIN_TOPIC");
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
            originTopic = properties.get("RETRY_TOPIC");
            if (StringUtils.hasText(originTopic)) {
                return originTopic.trim();
            }
        }
        return null;
    }

    /**
     * 清洗内部重试协议头与保留字
     */
    private Map<String, String> sanitizeHeaders(Map<String, String> originalProps) {
        Map<String, String> sanitized = new HashMap<>();
        if (originalProps != null) {
            for (Map.Entry<String, String> entry : originalProps.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String lowerKey = key.toLowerCase(Locale.ROOT);
                // 1. 过滤内部重试属性 (%RETRY%, __RETRY_, __ 开头等系统内部属性)
                if (key.startsWith("%RETRY%") || key.startsWith("__RETRY_") || key.startsWith("__")) {
                    continue;
                }
                // 2. 过滤常见内部重试与重连保留字
                if ("retry_topic".equals(lowerKey)
                        || "retry_count".equals(lowerKey)
                        || "retry_flag".equals(lowerKey)
                        || "reconnect_after".equals(lowerKey)
                        || "origin_message_id".equals(lowerKey)) {
                    continue;
                }
                // 3. 过滤旧的压缩头（由 RocketmqTemplate 重新评估并打标）
                if (RocketmqConstants.HEADER_COMPRESSION.equalsIgnoreCase(key)) {
                    continue;
                }
                sanitized.put(key, entry.getValue());
            }
        }
        // 清理旧的死信协议头（避免重复或脏数据干扰）
        sanitized.remove(RocketmqConstants.HEADER_DEAD_LETTER_REPLAYED);
        sanitized.remove(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_COUNT);
        sanitized.remove(RocketmqConstants.HEADER_DEAD_LETTER_REPLAY_TIME);
        return sanitized;
    }

    /**
     * 提取报文并解压
     */
    private byte[] extractAndDecompressBody(MessageView messageView, Map<String, String> properties) {
        ByteBuffer byteBuffer = messageView.getBody();
        if (byteBuffer == null || byteBuffer.remaining() == 0) {
            return new byte[0];
        }
        byte[] rawBytes = new byte[byteBuffer.remaining()];
        byteBuffer.duplicate().get(rawBytes);

        String compression = properties.get(RocketmqConstants.HEADER_COMPRESSION);
        if (RocketmqConstants.COMPRESSION_GZIP.equalsIgnoreCase(compression)) {
            try {
                return RocketmqMessageHelper.decompressGzip(rawBytes);
            } catch (IOException e) {
                log.warn("[ateng-boot-rocketmq] 死信重放解压 GZIP 报文失败，回退使用原始载荷: messageId={}",
                        messageView.getMessageId(), e);
                return rawBytes;
            }
        }
        return rawBytes;
    }
}
