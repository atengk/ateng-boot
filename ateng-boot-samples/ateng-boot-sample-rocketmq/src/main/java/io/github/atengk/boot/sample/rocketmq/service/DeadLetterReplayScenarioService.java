package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.model.DeadLetterReplayDetail;
import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.template.RocketmqDeadLetterReplayTemplate;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterMessageVO;
import io.github.atengk.boot.sample.rocketmq.dto.DeadLetterReplayVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private final RocketmqDeadLetterReplayTemplate deadLetterReplayTemplate;

    public List<MessageView> peekDeadLetters(String consumerGroup, int limit) {
        log.info("[DLQ-SCENARIO] 只读探查死信队列: consumerGroup={}, limit={}", consumerGroup, limit);
        return deadLetterReplayTemplate.peekDeadLetters(consumerGroup, limit);
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
