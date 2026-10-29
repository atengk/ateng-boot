package io.github.atengk.boot.rocketmq.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死信队列受控重放治理执行汇总结果模型
 *
 * <p>完整记录批量死信拉取量、重放成功量、防二次雪崩熔断拦截量及异常失败量，
 * 支撑企业级控制台运维对账、SRE 监控告警与审计追踪闭环。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class DeadLetterReplayResult implements Serializable {

    private final String dlqTopic;
    private final String consumerGroup;
    private int totalPolled;
    private int successCount;
    private int blockedCount;
    private int failedCount;
    private final List<DeadLetterReplayDetail> details = new ArrayList<>();

    public DeadLetterReplayResult(String dlqTopic, String consumerGroup) {
        this.dlqTopic = dlqTopic;
        this.consumerGroup = consumerGroup;
    }

    public static DeadLetterReplayResult empty(String dlqTopic, String consumerGroup) {
        return new DeadLetterReplayResult(dlqTopic, consumerGroup);
    }

    public synchronized void recordSuccess(String messageId, String newReceiptId, String bizKey, String targetTopic, int replayCount) {
        this.totalPolled++;
        this.successCount++;
        this.details.add(new DeadLetterReplayDetail(
                messageId,
                newReceiptId,
                bizKey,
                targetTopic,
                replayCount,
                DeadLetterReplayDetail.Status.SUCCESS,
                null
        ));
    }

    public synchronized void recordBlocked(String messageId, String bizKey, int replayCount, String reason) {
        this.totalPolled++;
        this.blockedCount++;
        this.details.add(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                null,
                replayCount,
                DeadLetterReplayDetail.Status.BLOCKED_BY_MAX_ATTEMPTS,
                reason
        ));
    }

    public synchronized void recordUnresolvedTopic(String messageId, String bizKey, int replayCount, String reason) {
        this.totalPolled++;
        this.failedCount++;
        this.details.add(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                null,
                replayCount,
                DeadLetterReplayDetail.Status.UNRESOLVED_TOPIC,
                reason
        ));
    }

    public synchronized void recordFailed(String messageId, String bizKey, String targetTopic, int replayCount, String error) {
        this.totalPolled++;
        this.failedCount++;
        this.details.add(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                targetTopic,
                replayCount,
                DeadLetterReplayDetail.Status.FAILED,
                error
        ));
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public int getTotalPolled() {
        return totalPolled;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getBlockedCount() {
        return blockedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public List<DeadLetterReplayDetail> getDetails() {
        return Collections.unmodifiableList(details);
    }

    public boolean isAllSuccess() {
        return totalPolled > 0 && successCount == totalPolled;
    }

    public boolean hasFailures() {
        return blockedCount > 0 || failedCount > 0;
    }

    @Override
    public String toString() {
        return "DeadLetterReplayResult{" +
                "dlqTopic='" + dlqTopic + '\'' +
                ", consumerGroup='" + consumerGroup + '\'' +
                ", totalPolled=" + totalPolled +
                ", successCount=" + successCount +
                ", blockedCount=" + blockedCount +
                ", failedCount=" + failedCount +
                ", detailsCount=" + details.size() +
                '}';
    }
}
