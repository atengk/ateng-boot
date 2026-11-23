package io.github.atengk.boot.rocketmq.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeadLetterReplayResult 并发安全与快照防御单元测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@DisplayName("DeadLetterReplayResult 并发记录与不可变快照单元测试")
class DeadLetterReplayResultTest {

    @Test
    @DisplayName("getDetails 返回不可变防御性快照：后续写入不影响历史快照且严禁外部修改")
    void testGetDetailsReturnsDefensiveImmutableSnapshot() {
        DeadLetterReplayResult result = new DeadLetterReplayResult("%DLQ%group", "group");
        result.recordSuccess("MSG-1", "NEW-1", "BIZ-1", "order_topic", 1);

        List<DeadLetterReplayDetail> snapshot = result.getDetails();
        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(null)).isInstanceOf(UnsupportedOperationException.class);

        result.recordFailed("MSG-2", "BIZ-2", "order_topic", 2, "timeout");
        assertThat(snapshot).hasSize(1);
        assertThat(result.getDetails()).hasSize(2);
    }

    @Test
    @DisplayName("多线程并发写入与并发读取快照：计数与明细严格一致且无 ConcurrentModificationException")
    void testConcurrentRecordAndSnapshotReadConsistency() throws Exception {
        DeadLetterReplayResult result = new DeadLetterReplayResult("%DLQ%group", "group");
        int writers = 8;
        int opsPerWriter = 50;
        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int workerId = w;
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < opsPerWriter; i++) {
                        int kind = (workerId + i) % 4;
                        switch (kind) {
                            case 0 -> result.recordSuccess("M-" + workerId + "-" + i, "R-" + i, "K-" + i, "t", 1);
                            case 1 -> result.recordBlocked("M-" + workerId + "-" + i, "K-" + i, 3, "max");
                            case 2 -> result.recordUnresolvedTopic("M-" + workerId + "-" + i, "K-" + i, 1, "missing");
                            default -> result.recordFailed("M-" + workerId + "-" + i, "K-" + i, "t", 1, "err");
                        }
                        if (i % 5 == 0) {
                            assertThat(result.getDetails().size()).isGreaterThanOrEqualTo(0);
                            assertThat(result.toString()).contains("DeadLetterReplayResult");
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }));
        }

        gate.countDown();
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);

        int expectedTotal = writers * opsPerWriter;
        assertThat(result.getTotalPolled()).isEqualTo(expectedTotal);
        assertThat(result.getSuccessCount() + result.getBlockedCount() + result.getFailedCount()).isEqualTo(expectedTotal);
        assertThat(result.getDetails()).hasSize(expectedTotal);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.isAllSuccess()).isFalse();
    }
}
