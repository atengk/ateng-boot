package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息出站投递异常单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("MessageDeliveryException 单元测试")
class MessageDeliveryExceptionTest {

    @Test
    @DisplayName("验证全参数构造与因果链保留")
    void testFullConstructor() {
        IOException cause = new IOException("Broker 连接不可达");
        MessageDeliveryException ex = new MessageDeliveryException(
                "rabbitmq",
                "trade.order.exchange",
                "order-10086",
                CommonResultCode.INTERNAL_SERVER_ERROR,
                "发送确认超时",
                cause
        );

        assertEquals("rabbitmq", ex.getMqType());
        assertEquals("trade.order.exchange", ex.getTopic());
        assertEquals("order-10086", ex.getBizKey());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertEquals("发送确认超时", ex.getMsg());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("验证重载构造函数与默认状态码")
    void testOverloadedConstructors() {
        IOException cause = new IOException("NACK 拒绝");
        MessageDeliveryException ex1 = new MessageDeliveryException(
                "kafka",
                "audit-log-topic",
                "biz-999",
                "Kafka 投递失败",
                cause
        );

        assertEquals("kafka", ex1.getMqType());
        assertEquals("audit-log-topic", ex1.getTopic());
        assertEquals("biz-999", ex1.getBizKey());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex1.getCode());
        assertSame(cause, ex1.getCause());

        MessageDeliveryException ex2 = new MessageDeliveryException(
                "rocketmq",
                "order-topic",
                "order-123",
                "无 Cause 报错"
        );
        assertEquals("rocketmq", ex2.getMqType());
        assertNull(ex2.getCause());
    }
}
