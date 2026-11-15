package io.github.atengk.boot.rocketmq.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RocketMQ 结构化死信诊断不可变记录模型
 *
 * <p>由 {@code RocketmqDeadLetterReplayTemplate.peekDiagnosed} 从 {@code %DLQ%<ConsumerGroup>} 只读解析产出，
 * 完整携带死信来源定位、故障诊断元数据、安全解码后的文本或有界十六进制载荷预览以及不可变属性快照。</p>
 *
 * @param messageId           死信队列中的消息唯一 ID
 * @param originalMessageId   触发死信前的原始消息 ID（未携带时为 {@code null}）
 * @param dlqTopic            死信队列主题名称（例如 {@code %DLQ%GID_ORDER}）
 * @param originalTopic       原始业务主题（多级回溯解析，未命中时为 {@code null}）
 * @param consumerGroup       关联消费组名称
 * @param tag                 消息 Tag 标签（未设置时为 {@code null}）
 * @param bizKey              业务唯一键（保底非空字符串）
 * @param keys                不可变业务 Keys 列表
 * @param messageGroup        顺序消息分区键（非顺序消息时为 {@code null}）
 * @param deliveryAttempt     触发死信时的投递尝试次数
 * @param replayCount         历史累计重放次数
 * @param replayed            是否曾经经过重放
 * @param reason              死信归档/毒丸触发原因分类（未携带时为 {@code null}）
 * @param deadLetterTimestamp 死信归档毫秒时间戳（未携带时为 {@code null}）
 * @param exceptionClassName  异常全限定类名（未携带时为 {@code null}）
 * @param exceptionMessage    异常摘要信息（未携带时为 {@code null}）
 * @param payloadAsString     安全解码后的 UTF-8 载荷文本，或在解压/编码损坏时降级的有界十六进制预览
 * @param hexPreview          是否因损坏二进制或非 UTF-8 字节而回退为十六进制预览
 * @param payloadSizeBytes    解码/预览前对应有效载荷的字节长度
 * @param properties          不可变扩展属性头快照映射
 * @author Ateng
 * @since 2026-11-15
 */
public record RocketmqDeadLetterRecord(
        String messageId,
        String originalMessageId,
        String dlqTopic,
        String originalTopic,
        String consumerGroup,
        String tag,
        String bizKey,
        List<String> keys,
        String messageGroup,
        int deliveryAttempt,
        int replayCount,
        boolean replayed,
        String reason,
        Long deadLetterTimestamp,
        String exceptionClassName,
        String exceptionMessage,
        String payloadAsString,
        boolean hexPreview,
        int payloadSizeBytes,
        Map<String, String> properties
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public RocketmqDeadLetterRecord {
        bizKey = bizKey != null ? bizKey : "";
        keys = keys == null ? List.of() : keys.stream().filter(Objects::nonNull).toList();
        payloadAsString = payloadAsString != null ? payloadAsString : "";
        if (properties == null || properties.isEmpty()) {
            properties = Map.of();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
            properties = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
        }
    }
}
