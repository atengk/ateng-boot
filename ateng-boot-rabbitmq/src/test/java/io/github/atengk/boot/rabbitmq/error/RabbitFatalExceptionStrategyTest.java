package io.github.atengk.boot.rabbitmq.error;

import com.fasterxml.jackson.core.JsonParseException;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.messaging.converter.MessageConversionException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RabbitFatalExceptionStrategy 毒丸与死信终点隔离判定单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitFatalExceptionStrategy 毒丸与死信终点隔离单元测试")
class RabbitFatalExceptionStrategyTest {

    private final RabbitFatalExceptionStrategy strategy = new RabbitFatalExceptionStrategy();

    @Test
    @DisplayName("毒丸异常判定：JSR-303、非法参数、格式转换等不可自愈异常判定为 Fatal")
    void testPoisonPillExceptionsAreFatal() {
        Message message = new Message("poison".getBytes());

        // 1. ValidationException 毒丸
        ListenerExecutionFailedException valEx = new ListenerExecutionFailedException(
                "listener failed", new ValidationException("字段不合法"), message);
        assertThat(strategy.isFatal(valEx)).isTrue();

        // 2. IllegalArgumentException 毒丸
        ListenerExecutionFailedException argEx = new ListenerExecutionFailedException(
                "listener failed", new IllegalArgumentException("非法入参"), message);
        assertThat(strategy.isFatal(argEx)).isTrue();

        // 3. MessageConversionException 转换失败
        ListenerExecutionFailedException convEx = new ListenerExecutionFailedException(
                "listener failed", new MessageConversionException("反序列化无法匹配目标类"), message);
        assertThat(strategy.isFatal(convEx)).isTrue();

        // 4. JsonParseException 格式畸形
        ListenerExecutionFailedException jsonEx = new ListenerExecutionFailedException(
                "listener failed", new JsonParseException(null, "畸形 JSON"), message);
        assertThat(strategy.isFatal(jsonEx)).isTrue();
    }

    @Test
    @DisplayName("可自愈异常判定：网络抖动、超时等异常判定为非 Fatal，允许进入本地重试")
    void testTransientExceptionsAreNotFatal() {
        Message message = new Message("normal".getBytes());

        ListenerExecutionFailedException ioEx = new ListenerExecutionFailedException(
                "listener failed", new IOException("网络暂时中断"), message);
        assertThat(strategy.isFatal(ioEx)).isFalse();

        ListenerExecutionFailedException timeoutEx = new ListenerExecutionFailedException(
                "listener failed", new TimeoutException("下游响应超时"), message);
        assertThat(strategy.isFatal(timeoutEx)).isFalse();

        ListenerExecutionFailedException bizEx = new ListenerExecutionFailedException(
                "listener failed", new RuntimeException("数据库死锁重试"), message);
        assertThat(strategy.isFatal(bizEx)).isFalse();
    }

    @Test
    @DisplayName("死信终点隔离守护 (Terminal Invariant)：当消费队列本身为死信队列 (带有 x-death 或 .dlq) 时，任何异常均判定为 Fatal 触发丢弃，坚决阻断死信循环")
    void testTerminalInvariantOnDeadLetterQueue() {
        // 场景 1: Message 携带 x-death 头部
        MessageProperties propsWithXDeath = new MessageProperties();
        propsWithXDeath.setHeader("x-death", List.of(Map.of("queue", "origin.queue", "reason", "rejected")));
        Message dlqMessage1 = new Message("dead-payload".getBytes(), propsWithXDeath);

        // 即便是普通的 IOException，在 DLQ 中也必须判定为 Fatal，封死循环
        ListenerExecutionFailedException dlqEx1 = new ListenerExecutionFailedException(
                "dlq failed", new IOException("死信处理故障"), dlqMessage1);
        assertThat(strategy.isFatal(dlqEx1)).isTrue();

        // 场景 2: 队列名称为 trade.order.dlq
        MessageProperties propsDlqName = new MessageProperties();
        propsDlqName.setConsumerQueue("trade.order.dlq");
        Message dlqMessage2 = new Message("dead-payload-2".getBytes(), propsDlqName);

        ListenerExecutionFailedException dlqEx2 = new ListenerExecutionFailedException(
                "dlq failed", new RuntimeException("普通运行时错误"), dlqMessage2);
        assertThat(strategy.isFatal(dlqEx2)).isTrue();
    }
}
