package io.github.atengk.boot.rabbitmq.template;

import io.github.atengk.boot.rabbitmq.helper.RabbitDeadLetterHelper;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * RabbitMQ 死信消息受控治理与重放门面
 * 负责死信队列消息的安全重放 (replay)、只读探查 (peek) 与队列清空 (purge)
 * 内置单批上限保护、剥离底层原死信错误头、注入 X-Dead-Letter-Replayed 溯源标记并严格防范二次死信雪崩死循环
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class RabbitDeadLetterReplayTemplate {

    public static final String HEADER_X_DEAD_LETTER_REPLAYED = "X-Dead-Letter-Replayed";
    public static final String HEADER_X_REPLAY_TIME = "X-Replay-Time";
    public static final String HEADER_X_REPLAY_COUNT = "X-Replay-Count";
    public static final String HEADER_X_ORIGINAL_DEAD_LETTER_QUEUE = "X-Original-Dead-Letter-Queue";
    public static final String HEADER_X_ORIGINAL_DEAD_LETTER_REASON = "X-Original-Dead-Letter-Reason";

    public static final int DEFAULT_REPLAY_LIMIT = 100;
    public static final int HARD_MAX_REPLAY_LIMIT = 1000;

    private final RabbitTemplate rabbitTemplate;
    private final RabbitmqProperties properties;
    private final MessagePropertiesConverter messagePropertiesConverter = new DefaultMessagePropertiesConverter();

    public RabbitDeadLetterReplayTemplate(RabbitTemplate rabbitTemplate, RabbitmqProperties properties) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /**
     * 获取底层原生 RabbitTemplate 实例
     *
     * @return RabbitTemplate 实例
     */
    public RabbitTemplate getRabbitTemplate() {
        return this.rabbitTemplate;
    }

    /**
     * 从死信队列受控拉取消息，安全清洗并重新入队至目标业务交换机与路由键 (使用默认单批上限 100)
     *
     * @param dlq              死信队列名称
     * @param targetExchange   重新投递的目标交换机
     * @param targetRoutingKey 重新投递的目标路由键
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlq, String targetExchange, String targetRoutingKey) {
        return replay(dlq, targetExchange, targetRoutingKey, properties.getDeadLetter().getDefaultReplayLimit(), null);
    }

    /**
     * 从死信队列受控拉取消息，使用指定最大条数上限重放
     *
     * @param dlq              死信队列名称
     * @param targetExchange   重新投递的目标交换机
     * @param targetRoutingKey 重新投递的目标路由键
     * @param limit            单批次最大重放数量 (1 ~ 1000)
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlq, String targetExchange, String targetRoutingKey, int limit) {
        return replay(dlq, targetExchange, targetRoutingKey, limit, null);
    }

    /**
     * 从死信队列受控拉取消息，使用指定谓词过滤器重放
     *
     * @param dlq              死信队列名称
     * @param targetExchange   重新投递的目标交换机
     * @param targetRoutingKey 重新投递的目标路由键
     * @param filter           消息过滤谓词，为 null 时默认不过滤全部重放
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlq, String targetExchange, String targetRoutingKey, Predicate<Message> filter) {
        return replay(dlq, targetExchange, targetRoutingKey, properties.getDeadLetter().getDefaultReplayLimit(), filter);
    }

    /**
     * 从死信队列受控拉取消息，使用指定条数上限与谓词过滤器安全重放
     *
     * @param dlq              死信队列名称
     * @param targetExchange   重新投递的目标交换机
     * @param targetRoutingKey 重新投递的目标路由键
     * @param limit            单批次最大重放数量 (1 ~ 1000)
     * @param filter           消息过滤谓词，为 null 时默认不过滤全部重放
     * @return 实际成功重放入队的消息数量
     */
    public int replay(String dlq, String targetExchange, String targetRoutingKey, int limit, Predicate<Message> filter) {
        Assert.hasText(dlq, "dlq 死信队列名称不能为空");
        Assert.hasText(targetExchange, "targetExchange 目标交换机名称不能为空");
        Assert.isTrue(limit > 0 && limit <= HARD_MAX_REPLAY_LIMIT,
                String.format("limit 必须在 1 到 %d 之间 (当前: %d)", HARD_MAX_REPLAY_LIMIT, limit));
        String effectiveRoutingKey = targetRoutingKey != null ? targetRoutingKey : "";

        return rabbitTemplate.execute(channel -> {
            int replayedCount = 0;
            List<Long> requeueTags = new ArrayList<>();
            MessagePropertiesConverter converter = this.messagePropertiesConverter;
            try {
                for (int i = 0; i < limit; i++) {
                    com.rabbitmq.client.GetResponse response = channel.basicGet(dlq, false);
                    if (response == null) {
                        break;
                    }
                    long deliveryTag = response.getEnvelope().getDeliveryTag();
                    MessageProperties props = converter.toMessageProperties(
                            response.getProps(), response.getEnvelope(), StandardCharsets.UTF_8.name());
                    Message message = new Message(response.getBody(), props);

                    // 1. 谓词过滤校验
                    if (filter != null && !filter.test(message)) {
                        requeueTags.add(deliveryTag);
                        continue;
                    }

                    // 2. 防二次死信雪崩熔断检查：防止多次重放失败的消息在业务队列与死信队列之间无限循环震荡
                    int currentReplayCount = extractReplayCount(props);
                    if (currentReplayCount >= properties.getDeadLetter().getMaxReplayCount()) {
                        log.error("[MQ-DLQ-REPLAY-BLOCKED] 死信消息已达最大重放次数上限 ({} 次)，已阻断重放，防范二次死信雪崩死循环: dlq={}, targetExchange={}, targetRoutingKey={}",
                                currentReplayCount, dlq, targetExchange, effectiveRoutingKey);
                        requeueTags.add(deliveryTag);
                        continue;
                    }

                    // 3. 安全清洗原始错误头并注入重放溯源元数据
                    Message replayedMessage = sanitizeAndPrepareReplayMessage(message, currentReplayCount + 1);

                    // 4. 发送到目标业务交换机
                    rabbitTemplate.send(targetExchange, effectiveRoutingKey, replayedMessage);

                    // 5. 确认消费并从死信队列可靠出队
                    channel.basicAck(deliveryTag, false);
                    replayedCount++;
                }
            } finally {
                // 确保跳过的消息 (过滤不匹配或超限阻断) 确定性安全回滚到死信队列
                for (Long tag : requeueTags) {
                    try {
                        channel.basicNack(tag, false, true);
                    } catch (Exception ex) {
                        log.warn("[MQ-REPLAY-NACK-WARN] 死信重放跳过消息回滚 NACK 异常: dlq={}, tag={}", dlq, tag, ex);
                    }
                }
            }
            log.info("[MQ-DLQ-REPLAY] 死信队列重放执行完成: dlq={}, targetExchange={}, targetRoutingKey={}, replayedCount={}, totalAttempted={}",
                    dlq, targetExchange, effectiveRoutingKey, replayedCount, limit);
            return replayedCount;
        });
    }

    /**
     * 只读探查死信队列头部指定数量的消息，提取元数据与载荷，不消费、不确认，原样保留在队列中
     *
     * @param dlq   死信队列名称
     * @param limit 探查最大条数 (1 ~ 1000)
     * @return 探查到的消息列表 (不可修改)
     */
    public List<Message> peek(String dlq, int limit) {
        Assert.hasText(dlq, "dlq 死信队列名称不能为空");
        Assert.isTrue(limit > 0 && limit <= HARD_MAX_REPLAY_LIMIT,
                String.format("limit 必须在 1 到 %d 之间 (当前: %d)", HARD_MAX_REPLAY_LIMIT, limit));

        return rabbitTemplate.execute(channel -> {
            List<Message> messages = new ArrayList<>();
            List<Long> deliveryTags = new ArrayList<>();
            MessagePropertiesConverter converter = this.messagePropertiesConverter;
            try {
                for (int i = 0; i < limit; i++) {
                    com.rabbitmq.client.GetResponse response = channel.basicGet(dlq, false);
                    if (response == null) {
                        break;
                    }
                    deliveryTags.add(response.getEnvelope().getDeliveryTag());
                    MessageProperties props = converter.toMessageProperties(
                            response.getProps(), response.getEnvelope(), StandardCharsets.UTF_8.name());
                    messages.add(new Message(response.getBody(), props));
                }
            } finally {
                // 严格通过 basicNack(tag, false, true) 将所有已探查的消息放回原队列，杜绝丢信
                for (Long tag : deliveryTags) {
                    try {
                        channel.basicNack(tag, false, true);
                    } catch (Exception ex) {
                        log.warn("[MQ-PEEK-NACK-WARN] 死信探查回滚 NACK 异常: dlq={}, tag={}", dlq, tag, ex);
                    }
                }
            }
            return Collections.unmodifiableList(messages);
        });
    }

    /**
     * 只读探查死信队列头部消息 (使用默认批次 100)
     *
     * @param dlq 死信队列名称
     * @return 探查到的消息列表
     */
    public List<Message> peek(String dlq) {
        return peek(dlq, properties.getDeadLetter().getDefaultReplayLimit());
    }

    /**
     * 清空指定死信队列中的全部积压残留
     *
     * @param dlq 死信队列名称
     * @return 清理被删除的消息总数
     */
    public int purge(String dlq) {
        Assert.hasText(dlq, "dlq 死信队列名称不能为空");
        return rabbitTemplate.execute(channel -> {
            int purged = channel.queuePurge(dlq).getMessageCount();
            log.info("[MQ-DLQ-PURGE] 成功清空死信队列: dlq={}, purgedCount={}", dlq, purged);
            return purged;
        });
    }

    /**
     * 安全清洗原死信消息属性，剥离底层的 x-death 结构并注入重放溯源标记
     */
    private Message sanitizeAndPrepareReplayMessage(Message original, int nextReplayCount) {
        MessageProperties oldProps = original.getMessageProperties();
        MessageProperties newProps = new MessageProperties();

        if (oldProps != null) {
            if (oldProps.getContentType() != null) newProps.setContentType(oldProps.getContentType());
            if (oldProps.getContentEncoding() != null) newProps.setContentEncoding(oldProps.getContentEncoding());
            if (oldProps.getCorrelationId() != null) newProps.setCorrelationId(oldProps.getCorrelationId());
            if (oldProps.getMessageId() != null) newProps.setMessageId(oldProps.getMessageId());
            newProps.setPriority(oldProps.getPriority());
            newProps.setDeliveryMode(oldProps.getDeliveryMode());

            // 提取原死信原因与队列备查
            String deathReason = RabbitDeadLetterHelper.getDeathReason(oldProps);
            String deathQueue = RabbitDeadLetterHelper.getOriginalQueue(oldProps);
            if (deathReason != null) {
                newProps.setHeader(HEADER_X_ORIGINAL_DEAD_LETTER_REASON, deathReason);
            }
            if (deathQueue != null) {
                newProps.setHeader(HEADER_X_ORIGINAL_DEAD_LETTER_QUEUE, deathQueue);
            }

            // 复制原有业务 headers，但剔除底层 x-death 错误头防止下游再次误判
            Map<String, Object> headers = oldProps.getHeaders();
            if (headers != null) {
                headers.forEach((k, v) -> {
                    if (!"x-death".equalsIgnoreCase(k)
                            && !"x-first-death-exchange".equalsIgnoreCase(k)
                            && !"x-first-death-queue".equalsIgnoreCase(k)
                            && !"x-first-death-reason".equalsIgnoreCase(k)) {
                        newProps.setHeader(k, v);
                    }
                });
            }
        }

        // 注入标准化重放标记与重放计数值
        newProps.setHeader(HEADER_X_DEAD_LETTER_REPLAYED, true);
        newProps.setHeader(HEADER_X_REPLAY_TIME, System.currentTimeMillis());
        newProps.setHeader(HEADER_X_REPLAY_COUNT, nextReplayCount);

        return new Message(original.getBody(), newProps);
    }

    /**
     * 提取消息当前的重放次数，不存在时返回 0
     */
    private int extractReplayCount(MessageProperties props) {
        if (props == null) {
            return 0;
        }
        Object countObj = props.getHeader(HEADER_X_REPLAY_COUNT);
        if (countObj instanceof Number num) {
            return num.intValue();
        } else if (countObj != null) {
            try {
                return Integer.parseInt(String.valueOf(countObj));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
