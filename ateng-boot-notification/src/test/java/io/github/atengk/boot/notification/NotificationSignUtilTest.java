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

        // 验证 URLDecoder 可正常反解，且遵循 RFC 3986（HMAC-SHA256 32字节 Base64 末尾必含 =，编码为 %3D，无空格 + 与 *）
        assertThat(sign1).endsWith("%3D").doesNotContain("+").doesNotContain("*").doesNotContain("%7E");
        String decoded = URLDecoder.decode(sign1, StandardCharsets.UTF_8);
        assertThat(decoded).isNotBlank().endsWith("=");

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
        assertThat(NotificationSignUtil.verifyHmacSha256Hex(secret, content, hexSign)).isTrue();
        assertThat(NotificationSignUtil.verifyHmacSha256Hex(secret, content, hexSign.toUpperCase())).isTrue();
        assertThat(NotificationSignUtil.verifyHmacSha256Hex(secret, "{\"msg\":\"tampered\"}", hexSign)).isFalse();
        assertThat(NotificationSignUtil.verifyHmacSha256Hex(null, content, hexSign)).isFalse();
        assertThat(NotificationSignUtil.verifyHmacSha256Hex(secret, content, null)).isFalse();
    }

    @Test
    @DisplayName("测试通用 Webhook 防重放加签与常量时间对称验签")
    void testSignAndVerifyGenericWebhook() {
        String secret = "generic-webhook-secret";
        long timestamp = 1700000000000L;
        String payload = "{\"event\":\"alert\",\"level\":\"P0\"}";

        String sig = NotificationSignUtil.signGenericWebhook(secret, timestamp, payload);
        assertThat(sig).isNotNull().hasSize(64).matches("[0-9a-f]{64}");

        // 对称验签通过（兼容大小写十六进制签名）
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp, payload, sig)).isTrue();
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp, payload, sig.toUpperCase())).isTrue();

        // 防重放与防篡改校验：时间戳错位、报文被篡改、密钥不匹配或签名长度错误均返回 false
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp + 1L, payload, sig)).isFalse();
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp, "{\"event\":\"alert\",\"level\":\"P1\"}", sig)).isFalse();
        assertThat(NotificationSignUtil.verifyGenericWebhook("wrong-secret", timestamp, payload, sig)).isFalse();
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp, payload, "invalid-sig")).isFalse();
        assertThat(NotificationSignUtil.verifyGenericWebhook(null, timestamp, payload, sig)).isFalse();
        assertThat(NotificationSignUtil.verifyGenericWebhook(secret, timestamp, payload, null)).isFalse();
    }
}
