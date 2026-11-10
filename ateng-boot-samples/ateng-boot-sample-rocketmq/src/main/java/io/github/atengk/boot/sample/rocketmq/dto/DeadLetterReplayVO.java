package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 死信重放治理结果视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterReplayVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 死信主题
     */
    private String dlqTopic;

    /**
     * 消费组
     */
    private String consumerGroup;

    /**
     * 拉取的死信总量
     */
    private int totalPulled;

    /**
     * 成功重放数量
     */
    private int successCount;

    /**
     * 触发防二次雪崩熔断拦截数量
     */
    private int blockedCount;

    /**
     * 重放失败数量
     */
    private int failedCount;

    /**
     * 明细记录列表
     */
    private List<ReplayDetailVO> details;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplayDetailVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String originalMessageId;
        private String newReceiptId;
        private String bizKey;
        private String targetTopic;
        private String status;
        private int replayCount;
        private String reason;
    }
}
