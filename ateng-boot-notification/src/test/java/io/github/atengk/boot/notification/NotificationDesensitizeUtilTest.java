package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协同通知凭据与地址脱敏工具测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("协同通知脱敏安全工具测试")
class NotificationDesensitizeUtilTest {

    @Test
    @DisplayName("测试 Webhook URL 查询参数掩码脱敏")
    void testMaskWebhookWithQueryParams() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=68abcdef1234567890abcdef";
        String masked = NotificationDesensitizeUtil.maskWebhook(url);

        assertThat(masked)
                .startsWith("https://oapi.dingtalk.com/robot/send?")
                .endsWith("cdef")
                .doesNotContain("68abcdef1234567890")
                .contains("*");
    }

    @Test
    @DisplayName("测试 Webhook URL 路径参数掩码脱敏")
    void testMaskWebhookWithPathParams() {
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/abc12345-6789-abcd-ef01-234567890abc";
        String masked = NotificationDesensitizeUtil.maskWebhook(url);

        assertThat(masked)
                .startsWith("https://open.feishu.cn/open-apis/bot/v2/hook/")
                .endsWith("0abc")
                .doesNotContain("abc12345-6789-abcd")
                .contains("*");
    }

    @Test
    @DisplayName("测试超短或边界 URL 脱敏")
    void testMaskWebhookEdgeCases() {
        assertThat(NotificationDesensitizeUtil.maskWebhook(null)).isNull();
        assertThat(NotificationDesensitizeUtil.maskWebhook("")).isEmpty();
        assertThat(NotificationDesensitizeUtil.maskWebhook("http://a.cn")).isEqualTo("******");
    }

    @Test
    @DisplayName("测试加签 Secret 掩码脱敏")
    void testMaskSecret() {
        assertThat(NotificationDesensitizeUtil.maskSecret(null)).isNull();
        assertThat(NotificationDesensitizeUtil.maskSecret("")).isEmpty();
        assertThat(NotificationDesensitizeUtil.maskSecret("12345")).isEqualTo("******");

        String secret = "SEC1234567890abcdef123456";
        String masked = NotificationDesensitizeUtil.maskSecret(secret);

        assertThat(masked)
                .startsWith("SEC")
                .endsWith("456")
                .doesNotContain("1234567890abcdef")
                .contains("*");
    }
}
