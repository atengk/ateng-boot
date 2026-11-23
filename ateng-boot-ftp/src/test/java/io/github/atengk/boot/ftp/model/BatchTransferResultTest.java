package io.github.atengk.boot.ftp.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量传输聚合回执模型单测
 *
 * @author Ateng
 * @since 2026-10-30
 */
class BatchTransferResultTest {

    @Test
    @DisplayName("验证 BatchTransferResult 全成功状态断言")
    void testAllSuccess() {
        BatchTransferResult result = BatchTransferResult.builder()
                .totalFiles(10)
                .successCount(8)
                .failureCount(0)
                .skippedCount(2)
                .transferredBytes(2048)
                .duration(Duration.ofMillis(120))
                .build();

        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.getTotalFiles()).isEqualTo(10);
        assertThat(result.getSuccessCount()).isEqualTo(8);
        assertThat(result.getFailureCount()).isEqualTo(0);
        assertThat(result.getSkippedCount()).isEqualTo(2);
        assertThat(result.getTransferredBytes()).isEqualTo(2048);
        assertThat(result.getFailures()).isEmpty();
    }

    @Test
    @DisplayName("验证 BatchTransferResult 包含失败时的状态与追加")
    void testHasFailure() {
        BatchTransferResult result = BatchTransferResult.builder()
                .totalFiles(5)
                .successCount(3)
                .duration(Duration.ofMillis(300))
                .build();

        TransferFailureItem item = TransferFailureItem.of("/data/err.txt", new RuntimeException("IO Error"));
        result.addFailure(item);

        assertThat(result.isAllSuccess()).isFalse();
        assertThat(result.hasFailure()).isTrue();
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getFailures()).hasSize(1);
        assertThat(result.getFailures().get(0).getPath()).isEqualTo("/data/err.txt");
        assertThat(result.getFailures().get(0).getErrorMessage()).isEqualTo("IO Error");
        assertThat(result.getFailures().get(0).getExceptionType()).isEqualTo(RuntimeException.class.getName());
        assertThat(result.getFailures().get(0).getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("验证空安全保底防御与构造器")
    void testNullSafety() {
        BatchTransferResult result = new BatchTransferResult();
        result.setFailures(null);

        assertThat(result.getFailures()).isNotNull().isEmpty();
        assertThat(result.isAllSuccess()).isTrue();
        assertThat(result.hasFailure()).isFalse();

        TransferFailureItem failure = new TransferFailureItem("test.txt", "err", "Exception", Instant.now());
        assertThat(failure.getPath()).isEqualTo("test.txt");
        assertThat(failure.getErrorMessage()).isEqualTo("err");
        assertThat(failure.getExceptionType()).isEqualTo("Exception");
        assertThat(failure.getFailedAt()).isNotNull();
    }
}
