package io.github.atengk.boot.mail.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量发信聚合回执模型单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("BatchMailSendResult 领域模型测试")
class BatchMailSendResultTest {

    @Test
    @DisplayName("全量成功时 isAllSuccess 应为 true，hasFailure 为 false")
    void testAllSuccess() {
        MailMessage msg1 = new MailMessage().to("user1@example.com").subject("S1");
        MailMessage msg2 = new MailMessage().to("user2@example.com").subject("S2");

        MailSendResult r1 = MailSendResult.success("<m1@domain>", List.of("user1@example.com"), Duration.ofMillis(10));
        MailSendResult r2 = MailSendResult.success("<m2@domain>", List.of("user2@example.com"), Duration.ofMillis(15));

        MailSendResultItem item1 = MailSendResultItem.success(0, msg1, r1);
        MailSendResultItem item2 = MailSendResultItem.success(1, msg2, r2);

        BatchMailSendResult batchResult = BatchMailSendResult.builder()
                .totalCount(2)
                .successCount(2)
                .failureCount(0)
                .duration(Duration.ofMillis(25))
                .items(List.of(item1, item2))
                .build();

        assertThat(batchResult.isAllSuccess()).isTrue();
        assertThat(batchResult.hasFailure()).isFalse();
        assertThat(batchResult.getSuccessItems()).hasSize(2);
        assertThat(batchResult.getFailureItems()).isEmpty();
    }

    @Test
    @DisplayName("存在失败项时 hasFailure 应为 true，getFailureItems 正确返回")
    void testPartialFailure() {
        MailMessage msg1 = new MailMessage().to("user1@example.com").subject("S1");
        MailMessage msg2 = new MailMessage().to("fail@example.com").subject("S2");

        MailSendResult r1 = MailSendResult.success("<m1@domain>", List.of("user1@example.com"), Duration.ofMillis(10));
        MailSendResultItem item1 = MailSendResultItem.success(0, msg1, r1);
        MailSendResultItem item2 = MailSendResultItem.failure(1, msg2, "连接超时", new RuntimeException("Timeout"));

        BatchMailSendResult batchResult = BatchMailSendResult.builder()
                .totalCount(2)
                .successCount(1)
                .failureCount(1)
                .duration(Duration.ofMillis(30))
                .items(List.of(item1, item2))
                .build();

        assertThat(batchResult.isAllSuccess()).isFalse();
        assertThat(batchResult.hasFailure()).isTrue();
        assertThat(batchResult.getSuccessItems()).containsExactly(item1);
        assertThat(batchResult.getFailureItems()).containsExactly(item2);
    }

    @Test
    @DisplayName("空项保底返回安全非空列表")
    void testEmptyItems() {
        BatchMailSendResult emptyResult = BatchMailSendResult.builder()
                .totalCount(0)
                .successCount(0)
                .failureCount(0)
                .duration(Duration.ZERO)
                .items(null)
                .build();

        assertThat(emptyResult.isAllSuccess()).isFalse();
        assertThat(emptyResult.hasFailure()).isFalse();
        assertThat(emptyResult.getSuccessItems()).isEmpty();
        assertThat(emptyResult.getFailureItems()).isEmpty();
    }
}
