package io.github.atengk.boot.sample.kafka.service;

import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper;
import io.github.atengk.boot.kafka.helper.KafkaDeadLetterHelper.DeadLetterRecord;
import io.github.atengk.boot.kafka.template.KafkaDeadLetterReplayTemplate;
import io.github.atengk.boot.kafka.template.KafkaReliableTemplate;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;

/**
 * 死信治理与诊断重放场景服务
 *
 * <p>演示毒丸报文投递、结构化死信诊断 (KafkaDeadLetterHelper)、
 * 安全探查与受控重放 (KafkaDeadLetterReplayTemplate) 以及物理清空。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterReplayScenarioService {

    private final KafkaReliableTemplate kafkaReliableTemplate;
    private final ObjectProvider<KafkaDeadLetterReplayTemplate> replayTemplateProvider;

    /**
     * 发送毒丸报文触发死信转移
     */
    public SendResult<Object, Object> sendPoisonPill(String poisonData) {
        return kafkaReliableTemplate.sendReliable(KafkaScenarioTopologyConfig.TOPIC_POISON, "poison-key", poisonData);
    }

    /**
     * 解析死信记录为结构化诊断模型
     */
    public DeadLetterRecord diagnose(ConsumerRecord<?, ?> record) {
        return KafkaDeadLetterHelper.parse(record);
    }

    /**
     * 只读探查死信主题消息（不移动消费位移）
     */
    public List<ConsumerRecord<byte[], byte[]>> peekDeadLetters(String dltTopic, int limit) {
        KafkaDeadLetterReplayTemplate replayTemplate = replayTemplateProvider.getIfAvailable();
        if (replayTemplate == null) {
            return List.of();
        }
        return replayTemplate.peek(dltTopic, limit);
    }

    /**
     * 安全重放死信消息回目标业务主题
     */
    public int replayDeadLetters(String dltTopic, String targetTopic, int limit) {
        KafkaDeadLetterReplayTemplate replayTemplate = replayTemplateProvider.getIfAvailable();
        if (replayTemplate == null) {
            return 0;
        }
        return replayTemplate.replay(dltTopic, targetTopic, limit);
    }

    /**
     * 带谓词过滤的安全死信重放
     */
    public int replayDeadLetters(String dltTopic, String targetTopic, int limit, Predicate<ConsumerRecord<?, ?>> filter) {
        KafkaDeadLetterReplayTemplate replayTemplate = replayTemplateProvider.getIfAvailable();
        if (replayTemplate == null) {
            return 0;
        }
        return replayTemplate.replay(dltTopic, targetTopic, limit, filter);
    }

    /**
     * 物理清空死信主题
     */
    public void purgeDeadLetters(String dltTopic) {
        KafkaDeadLetterReplayTemplate replayTemplate = replayTemplateProvider.getIfAvailable();
        if (replayTemplate != null) {
            replayTemplate.purge(dltTopic);
        }
    }
}
