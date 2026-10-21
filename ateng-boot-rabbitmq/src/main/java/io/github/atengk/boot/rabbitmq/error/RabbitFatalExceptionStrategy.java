package io.github.atengk.boot.rabbitmq.error;

import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.util.List;
import java.util.Map;

/**
 * RabbitMQ 毒丸与致命不可自愈异常判定策略
 * 集成 Core 模块 FatalExceptionClassifier 判定毒丸异常，指导容器 0 次重试快速送入 DLQ，
 * 并严守死信终点隔离不变量 (Terminal Invariant)，杜绝死信在队列间互咬死循环
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RabbitFatalExceptionStrategy extends ConditionalRejectingErrorHandler.DefaultExceptionStrategy {

    @Override
    public boolean isFatal(Throwable t) {
        // 1. 死信终点隔离守护 (Terminal Invariant)：若消息本身来源于死信队列 (x-death 存在或队列名包含 dlq)，发生任何异常均判定为致命以防死循环
        if (t instanceof ListenerExecutionFailedException lefe) {
            Message failedMessage = lefe.getFailedMessage();
            if (failedMessage != null) {
                List<Map<String, ?>> xDeath = failedMessage.getMessageProperties().getXDeathHeader();
                String queue = failedMessage.getMessageProperties().getConsumerQueue();
                boolean isDlq = (xDeath != null && !xDeath.isEmpty()) || (queue != null && (queue.endsWith(".dlq") || queue.contains(".dlq.") || queue.contains("dead-letter")));
                if (isDlq) {
                    log.error("[MQ-TERMINAL-DLQ-FAILURE] 当前消费队列属于死信队列，发生异常触发终点隔离 (Terminal Invariant) 丢弃防死循环: queue={}, error={}",
                            queue, t.getMessage());
                    return true;
                }
            }
        }

        // 2. 检查 Spring AMQP 默认致命异常 (如 MessageConversionException 等)
        if (super.isFatal(t)) {
            return true;
        }

        // 3. 递归因果链集成 Core 模块 FatalExceptionClassifier
        Throwable cause = t instanceof ListenerExecutionFailedException ? t.getCause() : t;
        if (cause != null && FatalExceptionClassifier.isFatal(cause)) {
            log.warn("[MQ-FATAL-POISON-PILL] 捕获不可自愈毒丸异常，快速判定为致命异常 (requeue=false) 送入死信队列: exception={}, message={}",
                    cause.getClass().getName(), cause.getMessage());
            return true;
        }

        return false;
    }
}
