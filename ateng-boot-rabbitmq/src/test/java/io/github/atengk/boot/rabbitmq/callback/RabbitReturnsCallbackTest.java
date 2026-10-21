package io.github.atengk.boot.rabbitmq.callback;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.rabbitmq.autoconfig.AtengRabbitmqAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * ReturnsCallback 与 RabbitUnroutableMessageCallback SPI 单元测试
 * 验证不可路由消息退回时输出 [MQ-RETURNS] 宏日志并触发 SPI 补偿回调
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("ReturnsCallback 与不可路由回调 SPI 单元测试")
class RabbitReturnsCallbackTest {

    private final AtengRabbitmqAutoConfiguration autoConfiguration = new AtengRabbitmqAutoConfiguration();

    @Test
    @DisplayName("不可路由消息退回：触发已注册的 RabbitUnroutableMessageCallback SPI")
    void testReturnsCallbackTriggersSpi() {
        RabbitUnroutableMessageCallback spi = mock(RabbitUnroutableMessageCallback.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitUnroutableMessageCallback> spiProvider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            java.util.function.Consumer<RabbitUnroutableMessageCallback> consumer = inv.getArgument(0);
            consumer.accept(spi);
            return null;
        }).when(spiProvider).ifAvailable(any());

        RabbitTemplate.ReturnsCallback returnsCallback = autoConfiguration.atengRabbitReturnsCallback(spiProvider);

        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-return-101");
        props.setHeader(MessageHeaderConstants.TRACE_ID, "trace-return-202");
        Message message = new Message("unroutable-payload".getBytes(StandardCharsets.UTF_8), props);

        ReturnedMessage returned = new ReturnedMessage(message, 312, "NO_ROUTE", "miss.exchange", "miss.key");

        returnsCallback.returnedMessage(returned);

        verify(spi, times(1)).onUnroutableMessage(returned);
    }

    @Test
    @DisplayName("无 SPI 注册时：正常输出宏日志且不抛出任何异常")
    void testReturnsCallbackWithoutSpi() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitUnroutableMessageCallback> spiProvider = mock(ObjectProvider.class);

        RabbitTemplate.ReturnsCallback returnsCallback = autoConfiguration.atengRabbitReturnsCallback(spiProvider);

        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-no-spi");
        Message message = new Message("payload".getBytes(StandardCharsets.UTF_8), props);

        ReturnedMessage returned = new ReturnedMessage(message, 312, "NO_ROUTE", "miss.exchange", "miss.key");

        assertThatCode(() -> returnsCallback.returnedMessage(returned)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SPI 回调执行抛出异常时：受控捕获并记录错误日志，不向外穿透阻断 Channel")
    void testReturnsCallbackSpiThrowsExceptionDoesNotCrash() {
        RabbitUnroutableMessageCallback buggySpi = returned -> {
            throw new RuntimeException("Database down during compensation");
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitUnroutableMessageCallback> spiProvider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            java.util.function.Consumer<RabbitUnroutableMessageCallback> consumer = inv.getArgument(0);
            consumer.accept(buggySpi);
            return null;
        }).when(spiProvider).ifAvailable(any());

        RabbitTemplate.ReturnsCallback returnsCallback = autoConfiguration.atengRabbitReturnsCallback(spiProvider);

        ReturnedMessage returned = new ReturnedMessage(new Message("crash".getBytes(StandardCharsets.UTF_8)), 312, "NO_ROUTE", "ex", "rk");

        // 验证即便 SPI 挂掉，也不向外抛出异常
        assertThatCode(() -> returnsCallback.returnedMessage(returned)).doesNotThrowAnyException();
    }
}
