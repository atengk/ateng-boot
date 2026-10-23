package io.github.atengk.boot.notification;

import io.github.atengk.boot.notification.util.NotificationSignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 协同通知出站加签安全算法测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("协同通知出站加签安全算法测试")
class NotificationSignUtilTest {

    @Test
    @DisplayName("测试钉钉加签确定性与格式")
    void testSignDingTalk() {
        String secret = "test-dingtalk-secret";
        long timestamp = 1600000000000L;

        String sign1 = NotificationSignUtil.signDingTalk(secret, timestamp);
        String sign2 = NotificationSignUtil.signDingTalk(secret, timestamp);

        assertThat(sign1).isNotNull().isNotBlank();
        // 幂等性与确定性
        assertThat(sign1).isEqualTo(sign2);

        // 验证 URLDecoder 可正常反解
        String decoded = URLDecoder.decode(sign1, StandardCharsets.UTF_8);
        assertThat(decoded).isNotBlank();

        // 空 Secret 保护
        assertThat(NotificationSignUtil.signDingTalk(null, timestamp)).isNull();
        assertThat(NotificationSignUtil.signDingTalk("  ", timestamp)).isNull();
    }

    @Test
    @DisplayName("测试飞书加签确定性与格式")
    void testSignFeishu() {
        String secret = "test-feishu-secret";
        long timestamp = 1600000000L;

        String sign1 = NotificationSignUtil.signFeishu(secret, timestamp);
        String sign2 = NotificationSignUtil.signFeishu(secret, timestamp);

        assertThat(sign1).isNotNull().isNotBlank();
        // 幂等性与确定性
        assertThat(sign1).isEqualTo(sign2);

        // 空 Secret 保护
        assertThat(NotificationSignUtil.signFeishu(null, timestamp)).isNull();
        assertThat(NotificationSignUtil.signFeishu("  ", timestamp)).isNull();
    }

    @Test
    @DisplayName("测试通用 HMAC-SHA256 十六进制与 Base64 签名")
    void testSignGenericHmacSha256() {
        String secret = "generic-secret";
        String content = "{\"msg\":\"hello\"}";

        String hexSign = NotificationSignUtil.signHmacSha256Hex(secret, content);
        assertThat(hexSign).isNotNull().hasSize(64);

        String base64Sign = NotificationSignUtil.signHmacSha256Base64(secret, content);
        assertThat(base64Sign).isNotNull().isNotBlank();

        // 空值校验
        assertThat(NotificationSignUtil.signHmacSha256Hex(null, content)).isNull();
        assertThat(NotificationSignUtil.signHmacSha256Base64(null, content)).isNull();
    }
}
