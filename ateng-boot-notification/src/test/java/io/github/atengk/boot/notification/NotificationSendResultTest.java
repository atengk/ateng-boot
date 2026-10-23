package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知发信结果模型单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("通知发信结果领域载荷测试")
class NotificationSendResultTest {

    @Test
    @DisplayName("测试构建发信成功结果")
    void testSuccessResult() {
        NotificationSendResult result = NotificationSendResult.success(
                "msg-123", "ops-channel", Platform.DINGTALK, Duration.ofMillis(120), "{\"errcode\":0}"
        );

        assertThat(result.getMessageId()).isEqualTo("msg-123");
        assertThat(result.getChannel()).isEqualTo("ops-channel");
        assertThat(result.getPlatform()).isEqualTo(Platform.DINGTALK);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCostDuration()).isEqualTo(Duration.ofMillis(120));
        assertThat(result.getRawResponse()).isEqualTo("{\"errcode\":0}");
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.toString()).contains("ops-channel").contains("DINGTALK");
    }

    @Test
    @DisplayName("测试构建发信失败结果")
    void testFailureResult() {
        NotificationSendResult result = NotificationSendResult.failure(
                "msg-456", "finance-channel", Platform.WECOM, Duration.ofMillis(80), "{\"errcode\":93000}", "Key 无效"
        );

        assertThat(result.getMessageId()).isEqualTo("msg-456");
        assertThat(result.getChannel()).isEqualTo("finance-channel");
        assertThat(result.getPlatform()).isEqualTo(Platform.WECOM);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCostDuration()).isEqualTo(Duration.ofMillis(80));
        assertThat(result.getRawResponse()).isEqualTo("{\"errcode\":93000}");
        assertThat(result.getErrorMessage()).isEqualTo("Key 无效");
    }
}
