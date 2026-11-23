package io.github.atengk.boot.sample.rabbitmq.service;

import io.github.atengk.boot.rabbitmq.template.RabbitDeadLetterReplayTemplate;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信受控治理与防雪崩重放业务服务
 * 演示使用 RabbitDeadLetterReplayTemplate 执行死信重放、只读探查与全量排空
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterReplayService {

    private final RabbitDeadLetterReplayTemplate replayTemplate;

    /**
     * 重放死信队列消息至目标业务队列
     *
     * @param limit 单批次最大重放数量
     * @return 实际重放成功入队的消息数
     */
    public int replayBizDeadLetters(int limit) {
        log.info("[SERVICE-REPLAY] 开始重放死信队列消息: dlq={}, limit={}",
                RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ, limit);
        int replayed = replayTemplate.replay(
                RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ,
                RabbitScenarioTopologyConfig.EXCHANGE_BIZ_DIRECT,
                RabbitScenarioTopologyConfig.ROUTING_KEY_BIZ,
                limit
        );
        log.info("[SERVICE-REPLAY] 死信队列消息重放完成: dlq={}, replayedCount={}",
                RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ, replayed);
        return replayed;
    }

    /**
     * 只读探查死信队列头部积压消息
     *
     * @param limit 探查条数
     * @return 探查到的消息列表
     */
    public List<Message> peekBizDeadLetters(int limit) {
        log.info("[SERVICE-REPLAY] 探查死信队列头部消息: dlq={}, limit={}",
                RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ, limit);
        return replayTemplate.peek(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ, limit);
    }

    /**
     * 清空死信队列全部积压
     *
     * @return 清空被删除的消息条数
     */
    public int purgeBizDeadLetters() {
        log.info("[SERVICE-REPLAY] 清空死信队列: dlq={}", RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ);
        return replayTemplate.purge(RabbitScenarioTopologyConfig.QUEUE_BIZ_DLQ);
    }
}
