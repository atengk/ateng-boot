package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayDetail;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.model.RocketmqDeadLetterRecord;
import io.github.atengk.boot.rocketmq.template.RocketmqDeadLetterReplayTemplate;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterMessageVO;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterPoisonPillRequest;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterReplayVO;
import io.github.atengk.boot.sample.rocketmq.dto.OrderProduceResponse;
import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 死信队列运维治理与受控重放场景业务服务
 *
 * <p>演示死信只读探查（零 ACK）、受控重放至业务主题、防二次雪崩熔断拦截与确定性源端确认。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterReplayScenarioService {

    private static final String POISON_TAG = "poison";
    private static final byte[] CORRUPTED_GZIP_BYTES = new byte[]{
            0x1F, (byte) 0x8B, 0x08, 0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, 0x7F
    };

    private final RocketmqDeadLetterReplayTemplate deadLetterReplayTemplate;
    private final TradeRocketmqProducer tradeRocketmqProducer;

    public List<MessageView> peekDeadLetters(String consumerGroup, int limit) {
        log.info("[DLQ-SCENARIO] 只读探查死信队列: consumerGroup={}, limit={}", consumerGroup, limit);
        return deadLetterReplayTemplate.peekDeadLetters(consumerGroup, limit);
    }

    /**
     * 结构化诊断探查目标消费组死信队列（零 ACK，含自动解压、UTF-8/HEX 预览与失败原因分类）
     *
     * @param consumerGroup 消费组名称
     * @param limit         最大拉取条数
     * @return 结构化死信诊断记录列表
     */
    public List<RocketmqDeadLetterRecord> peekDiagnosedDeadLetters(String consumerGroup, int limit) {
        log.info("[DLQ-SCENARIO] 结构化诊断探查死信队列: consumerGroup={}, limit={}", consumerGroup, limit);
        return deadLetterReplayTemplate.peekDiagnosed(consumerGroup, limit);
    }

    /**
     * 投递指定模式的样本毒丸消息至 {@code sample_order_topic}（Tag = {@code poison}），触发消费端毒丸防护与 %DLQ% 转储闭环
     *
     * @param request 毒丸触发请求（为空时默认触发 FATAL 模式）
     * @return 生产发送回执
     */
    public OrderProduceResponse triggerPoisonPill(DeadLetterPoisonPillRequest request) {
        DeadLetterPoisonPillRequest safeRequest = request != null ? request : new DeadLetterPoisonPillRequest();
        String mode = StringUtils.hasText(safeRequest.getMode())
                ? safeRequest.getMode().trim().toUpperCase(Locale.ROOT)
                : "FATAL";
        String bizKey = resolvePoisonBizKey(mode, safeRequest.getOrderId());

        SendReceipt receipt = switch (mode) {
            case "FATAL" -> sendFatalPoisonPill(bizKey, safeRequest);
            case "VALIDATION" -> sendValidationPoisonPill(bizKey);
            case "MALFORMED_JSON" -> sendMalformedJsonPoisonPill(bizKey, safeRequest.getRawPayload());
            case "CORRUPTED_GZIP" -> sendCorruptedGzipPoisonPill(bizKey);
            default -> throw new IllegalArgumentException("不支持的毒丸模式: " + mode);
        };

        log.info("[DLQ-SCENARIO] 已投递毒丸消息: mode={}, bizKey={}, msgId={}",
                mode, bizKey, receipt.getMessageId());
        return OrderProduceResponse.builder()
                .orderId(bizKey)
                .messageId(receipt.getMessageId().toString())
                .tag(POISON_TAG)
                .status("POISON_PILL_DISPATCHED:" + mode)
                .build();
    }

    private String resolvePoisonBizKey(String mode, String rawOrderId) {
        if (StringUtils.hasText(rawOrderId)) {
            String trimmed = rawOrderId.trim();
            if ("FATAL".equals(mode) && !trimmed.contains("POISON_FATAL")) {
                return "POISON_FATAL-" + trimmed;
            }
            return trimmed;
        }
        return "POISON_" + mode + "-" + System.currentTimeMillis();
    }

    private SendReceipt sendFatalPoisonPill(String bizKey, DeadLetterPoisonPillRequest request) {
        OrderMessage order = OrderMessage.builder()
                .orderId(bizKey)
                .amount(request.getAmount() != null ? request.getAmount().doubleValue() : 99.90)
                .customerName("poison-tester")
                .status("POISON_FATAL")
                .build();
        return tradeRocketmqProducer.sendOrderWithTag(order, POISON_TAG);
    }

    private SendReceipt sendValidationPoisonPill(String bizKey) {
        OrderMessage invalidOrder = OrderMessage.builder()
                .orderId(bizKey)
                .amount(-99.0)
                .customerName("poison-tester")
                .status("POISON_VALIDATION")
                .build();
        return tradeRocketmqProducer.sendOrderWithTag(invalidOrder, POISON_TAG);
    }

    private SendReceipt sendMalformedJsonPoisonPill(String bizKey, String rawPayload) {
        String payload = StringUtils.hasText(rawPayload) ? rawPayload : "{malformed-poison-json";
        return tradeRocketmqProducer.getRocketmqTemplate().send(
                TradeRocketmqProducer.TOPIC_ORDER,
                POISON_TAG,
                bizKey,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private SendReceipt sendCorruptedGzipPoisonPill(String bizKey) {
        Message message = ClientServiceProvider.loadService()
                .newMessageBuilder()
                .setTopic(TradeRocketmqProducer.TOPIC_ORDER)
                .setTag(POISON_TAG)
                .setKeys(bizKey)
                .addProperty("X-Biz-Key", bizKey)
                .addProperty(RocketmqConstants.HEADER_COMPRESSION, RocketmqConstants.COMPRESSION_GZIP)
                .setBody(CORRUPTED_GZIP_BYTES)
                .build();
        try {
            return tradeRocketmqProducer.getRocketmqTemplate().getProducer().send(message);
        } catch (ClientException e) {
            throw new MessageDeliveryException(
                    RocketmqConstants.MQ_TYPE,
                    TradeRocketmqProducer.TOPIC_ORDER,
                    bizKey,
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "发送损坏 GZIP 毒丸消息失败: " + e.getMessage(),
                    e);
        }
    }

    public DeadLetterReplayResult replayDeadLetters(String consumerGroup, int limit) {
        log.info("[DLQ-SCENARIO] 受控重放死信队列至原始主题: consumerGroup={}, limit={}", consumerGroup, limit);
        return deadLetterReplayTemplate.replayDeadLetters(consumerGroup, limit);
    }

    public DeadLetterReplayResult replayDeadLettersToTopic(String consumerGroup, int limit, String targetTopic) {
        log.info("[DLQ-SCENARIO] 受控重放死信队列至指定目标主题: consumerGroup={}, limit={}, targetTopic={}",
                consumerGroup, limit, targetTopic);
        return deadLetterReplayTemplate.replayDeadLetters(consumerGroup, limit, targetTopic);
    }

    public int discardDeadLetters(String consumerGroup, int limit) {
        log.warn("[DLQ-SCENARIO] 批量清理丢弃死信消息: consumerGroup={}, limit={}", consumerGroup, limit);
        return deadLetterReplayTemplate.discardDeadLetters(consumerGroup, limit);
    }

    public static DeadLetterMessageVO convertToMessageVO(MessageView mv) {
        if (mv == null) {
            return null;
        }
        String body = SimpleConsumerPullScenarioService.extractBodyString(mv.getBody());

        Map<String, String> props = mv.getProperties() != null ? mv.getProperties() : Collections.emptyMap();
        String originalTopic = props.getOrDefault("X-Original-Topic", props.get("x-original-topic"));
        String bizKey = props.getOrDefault("X-Biz-Key", props.get("x-biz-key"));
        int replayCount = 0;
        String replayCountStr = props.getOrDefault("X-Dead-Letter-Replay-Count", props.get("x-dead-letter-replay-count"));
        if (replayCountStr != null) {
            try {
                replayCount = Integer.parseInt(replayCountStr.trim());
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }

        return DeadLetterMessageVO.builder()
                .messageId(mv.getMessageId() != null ? mv.getMessageId().toString() : null)
                .dlqTopic(mv.getTopic())
                .originalTopic(originalTopic)
                .bizKey(bizKey)
                .tag(mv.getTag().orElse(null))
                .keys(mv.getKeys() != null ? new ArrayList<>(mv.getKeys()) : Collections.emptyList())
                .body(body)
                .userProperties(props)
                .deliveryAttempt(mv.getDeliveryAttempt())
                .replayCount(replayCount)
                .build();
    }

    public static DeadLetterReplayVO convertToReplayVO(DeadLetterReplayResult result) {
        if (result == null) {
            return null;
        }
        List<DeadLetterReplayVO.ReplayDetailVO> detailVOs = new ArrayList<>();
        if (result.getDetails() != null) {
            for (DeadLetterReplayDetail d : result.getDetails()) {
                detailVOs.add(DeadLetterReplayVO.ReplayDetailVO.builder()
                        .originalMessageId(d.messageId())
                        .newReceiptId(d.newReceiptId())
                        .bizKey(d.bizKey())
                        .targetTopic(d.targetTopic())
                        .status(d.status() != null ? d.status().name() : null)
                        .replayCount(d.replayCount())
                        .reason(d.errorMessage())
                        .build());
            }
        }
        return DeadLetterReplayVO.builder()
                .dlqTopic(result.getDlqTopic())
                .consumerGroup(result.getConsumerGroup())
                .totalPulled(result.getTotalPolled())
                .successCount(result.getSuccessCount())
                .blockedCount(result.getBlockedCount())
                .failedCount(result.getFailedCount())
                .details(detailVOs)
                .build();
    }
}
