package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协同通知异常体系与错误码契约单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class NotificationExceptionTest {

    @Test
    @DisplayName("验证 NotificationResultCode 错误码区间与语义完备性")
    void testNotificationResultCode() {
        assertEquals(5701, NotificationResultCode.NOTIFICATION_SEND_ERROR.getCode());
        assertEquals(5702, NotificationResultCode.NOTIFICATION_CONNECT_TIMEOUT.getCode());
        assertEquals(5703, NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR.getCode());
        assertEquals(5704, NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED.getCode());
        assertEquals(5705, NotificationResultCode.NOTIFICATION_CHANNEL_NOT_FOUND.getCode());
        assertEquals(5706, NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY.getCode());
        assertEquals(5707, NotificationResultCode.NOTIFICATION_TEMPLATE_RENDER_ERROR.getCode());
        assertEquals(5708, NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM.getCode());
        assertEquals(5709, NotificationResultCode.NOTIFICATION_PARAMETER_INVALID.getCode());
        assertEquals(5710, NotificationResultCode.NOTIFICATION_CIRCUIT_BROKEN.getCode());

        for (NotificationResultCode code : NotificationResultCode.values()) {
            assertTrue(code.getCode() >= 5701 && code.getCode() <= 5710, "错误码必须在 5701~5710 专属规划区间");
            assertNotNull(code.getMsg(), "错误信息不能为空");
            assertFalse(code.getMsg().isBlank(), "错误信息不能为空白");
        }
    }

    @Test
    @DisplayName("验证 NotificationException 继承 BusinessException 与因果链传递")
    void testNotificationException() {
        RuntimeException cause = new RuntimeException("root network reset");
        NotificationException ex = new NotificationException(NotificationResultCode.NOTIFICATION_CONNECT_TIMEOUT, "连接超时", cause);

        assertInstanceOf(BusinessException.class, ex);
        assertEquals(5702, ex.getCode());
        assertEquals("连接超时", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("验证 NotificationSendException 携带原始报文与渠道平台元数据")
    void testNotificationSendException() {
        IllegalStateException cause = new IllegalStateException("HTTP 500 Internal Error");
        NotificationSendException ex = new NotificationSendException(
                "ops-alert",
                "DINGTALK",
                "{\"errcode\":310000,\"errmsg\":\"sign not match\"}",
                NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR,
                "签名校验未通过",
                cause
        );

        assertEquals("ops-alert", ex.getChannelName());
        assertEquals("DINGTALK", ex.getPlatform());
        assertEquals("{\"errcode\":310000,\"errmsg\":\"sign not match\"}", ex.getRawResponse());
        assertEquals(5703, ex.getCode());
        assertEquals("签名校验未通过", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("验证 NotificationRateLimitException 结构化记录配额与等待时长")
    void testNotificationRateLimitException() {
        Duration wait = Duration.ofSeconds(2);
        NotificationRateLimitException ex = new NotificationRateLimitException(
                "finance-bot",
                20,
                wait,
                "频控等待超时"
        );

        assertEquals("finance-bot", ex.getChannelName());
        assertEquals(20, ex.getPermitsPerMinute());
        assertEquals(wait, ex.getWaitDuration());
        assertEquals(5704, ex.getCode());
        assertEquals("频控等待超时", ex.getMessage());
    }
}
