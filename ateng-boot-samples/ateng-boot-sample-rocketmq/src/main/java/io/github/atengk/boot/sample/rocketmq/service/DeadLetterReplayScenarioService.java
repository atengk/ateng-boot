package io.github.atengk.boot.sample.rocketmq.service;

import io.github.atengk.boot.rocketmq.model.DeadLetterReplayResult;
import io.github.atengk.boot.rocketmq.template.RocketmqDeadLetterReplayTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
