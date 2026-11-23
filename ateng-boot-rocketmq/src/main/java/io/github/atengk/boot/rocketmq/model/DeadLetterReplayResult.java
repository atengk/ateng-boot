package io.github.atengk.boot.rocketmq.model;

import io.github.atengk.boot.rocketmq.helper.RocketmqLockHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ReentrantLock lock = new ReentrantLock();
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

    public void recordSuccess(String messageId, String newReceiptId, String bizKey, String targetTopic, int replayCount) {
        recordEntry(new DeadLetterReplayDetail(
                messageId,
                newReceiptId,
                bizKey,
                targetTopic,
                replayCount,
                DeadLetterReplayDetail.Status.SUCCESS,
                null));
    }

    public void recordBlocked(String messageId, String bizKey, int replayCount, String reason) {
        recordEntry(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                null,
                replayCount,
                DeadLetterReplayDetail.Status.BLOCKED_BY_MAX_ATTEMPTS,
                reason));
    }

    public void recordUnresolvedTopic(String messageId, String bizKey, int replayCount, String reason) {
        recordEntry(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                null,
                replayCount,
                DeadLetterReplayDetail.Status.UNRESOLVED_TOPIC,
                reason));
    }

    public void recordFailed(String messageId, String bizKey, String targetTopic, int replayCount, String error) {
        recordEntry(new DeadLetterReplayDetail(
                messageId,
                null,
                bizKey,
                targetTopic,
                replayCount,
                DeadLetterReplayDetail.Status.FAILED,
                error));
    }

    private void recordEntry(DeadLetterReplayDetail detail) {
        RocketmqLockHelper.runWithLock(lock, () -> {
            this.totalPolled++;
            switch (detail.status()) {
                case SUCCESS -> this.successCount++;
                case BLOCKED_BY_MAX_ATTEMPTS -> this.blockedCount++;
                case UNRESOLVED_TOPIC, FAILED -> this.failedCount++;
            }
            this.details.add(detail);
        });
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public int getTotalPolled() {
        return RocketmqLockHelper.withLock(lock, () -> totalPolled);
    }

    public int getSuccessCount() {
        return RocketmqLockHelper.withLock(lock, () -> successCount);
    }

    public int getBlockedCount() {
        return RocketmqLockHelper.withLock(lock, () -> blockedCount);
    }

    public int getFailedCount() {
        return RocketmqLockHelper.withLock(lock, () -> failedCount);
    }

    public List<DeadLetterReplayDetail> getDetails() {
        return RocketmqLockHelper.withLock(lock, () -> List.copyOf(details));
    }

    public boolean isAllSuccess() {
        return RocketmqLockHelper.withLock(lock, () -> totalPolled > 0 && successCount == totalPolled);
    }

    public boolean hasFailures() {
        return RocketmqLockHelper.withLock(lock, () -> blockedCount > 0 || failedCount > 0);
    }

    @Override
    public String toString() {
        return RocketmqLockHelper.withLock(lock, () -> "DeadLetterReplayResult{" +
                "dlqTopic='" + dlqTopic + '\'' +
                ", consumerGroup='" + consumerGroup + '\'' +
                ", totalPolled=" + totalPolled +
                ", successCount=" + successCount +
                ", blockedCount=" + blockedCount +
                ", failedCount=" + failedCount +
                ", detailsCount=" + details.size() +
                '}');
    }
}
