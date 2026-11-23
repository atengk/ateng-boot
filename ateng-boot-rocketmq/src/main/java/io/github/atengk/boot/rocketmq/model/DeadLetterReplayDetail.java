package io.github.atengk.boot.rocketmq.model;

import java.io.Serializable;

/**
 * 单条死信消息重放执行明细模型
 *
 * <p>记录单条死信在重放过程中的消息标识、目标主题、重放次数、最终状态与异常原因。</p>
 *
 * @param messageId 原始死信 MessageId
 * @param newReceiptId 重投成功后的新 MessageId（投递失败或熔断拦截时为 null）
 * @param bizKey 业务唯一标识
 * @param targetTopic 目标投递主题
 * @param replayCount 本次重放累计次数
 * @param status 执行状态
 * @param errorMessage 失败或熔断原因说明
 * @author Ateng
 * @since 2026-10-30
 */
public record DeadLetterReplayDetail(
        String messageId,
        String newReceiptId,
        String bizKey,
        String targetTopic,
        int replayCount,
        Status status,
        String errorMessage
) implements Serializable {

    /**
     * 重放执行状态枚举
     */
    public enum Status {
        /**
         * 成功重新投递至目标主题并确认 ACK 原死信
         */
        SUCCESS,

        /**
         * 累计重放次数达到或超出上限，触发防二次雪崩熔断拦截
         */
        BLOCKED_BY_MAX_ATTEMPTS,

        /**
         * 无法解析原始业务主题且未显式指定目标主题
         */
        UNRESOLVED_TOPIC,

        /**
         * 投递目标主题失败或确认原死信失败
         */
        FAILED
    }
}
