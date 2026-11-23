package io.github.atengk.boot.rabbitmq.executor;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * RabbitMQ 批量消息逐条四维上下文隔离与单条容错执行器
 *
 * <p>同时提供静态调用入口与 Spring 受管 Bean 形态：
 * <ul>
 *   <li>静态方法面向原生 {@link Message} 列表逐条激活并确定性清理 {@link MessageContextPropagator.ContextScope}；</li>
 *   <li>Spring Bean 形态持有受管 {@link MessageConverter}，支持基于 Inferred Deserialization 将单条 {@link Message} 反序列化为强类型 POJO；</li>
 *   <li>{@code processBatchSafely} 隔离单条故障并区分幂等冲突（输出 {@code [MQ-IDEMPOTENT-DUPLICATE]} WARN 日志且静默跳过不触发失败回调）与常规异常。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Slf4j
public class RabbitBatchExecutor {

    private final MessageConverter messageConverter;

    /**
     * 构造持有受管消息转换器的批量执行器实例
     *
     * @param messageConverter RabbitMQ 消息转换器
     */
    public RabbitBatchExecutor(MessageConverter messageConverter) {
        this.messageConverter = Objects.requireNonNull(messageConverter, "messageConverter 不能为空");
    }

    /**
     * 生成批次全局 TraceId（前缀 "batch-"）
     *
     * @return 批次全局追踪标识
     */
    public static String generateBatchTraceId() {
        return "batch-" + IdUtil.fastSimpleUUID();
    }

    /**
     * 在单条原始消息的四维上下文中安全闭包执行逻辑，结束时确定性恢复调用线程现场
     *
     * @param message  单条 AMQP 消息
     * @param consumer 消费处理逻辑
     */
    public static void runInContext(Message message, Consumer<Message> consumer) {
        if (message == null || consumer == null) {
            return;
        }
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(extractHeaders(message))) {
            consumer.accept(message);
        }
    }

    /**
     * 在单条原始消息的四维上下文中安全闭包执行带返回值的逻辑，结束时确定性恢复调用线程现场
     *
     * @param message  单条 AMQP 消息
     * @param function 消费处理函数
     * @param <R>      返回值类型
     * @return 处理结果，入参为空时返回 null
     */
    public static <R> R callInContext(Message message, Function<Message, R> function) {
        if (message == null || function == null) {
            return null;
        }
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(extractHeaders(message))) {
            return function.apply(message);
        }
    }

    /**
     * 批量容错遍历原始消息列表（默认仅记录错误日志并静默跳过幂等冲突），单条失败不阻断余下消息
     *
     * @param messages  批量 AMQP 消息
     * @param processor 单条消息处理器
     */
    public static void processBatchSafely(Iterable<? extends Message> messages, Consumer<Message> processor) {
        processBatchSafely(messages, processor, null);
    }

    /**
     * 批量容错遍历原始消息列表：逐条隔离四维上下文，幂等重复静默跳过，常规失败回调 failureHandler
     *
     * @param messages       批量 AMQP 消息
     * @param processor      单条消息处理器
     * @param failureHandler 单条常规异常回调（可为 null；幂等异常不会触发该回调）
     */
    public static void processBatchSafely(
            Iterable<? extends Message> messages,
            Consumer<Message> processor,
            BiConsumer<Message, Exception> failureHandler) {
        if (messages == null || processor == null) {
            return;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            try {
                runInContext(message, processor);
            } catch (Exception ex) {
                handleMessageFailure(message, ex, failureHandler);
            }
        }
    }

    /**
     * 在单条消息的四维上下文中反序列化目标类型并执行消费逻辑
     *
     * @param message    单条 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param consumer   强类型消费逻辑
     * @param <T>        目标 POJO 泛型
     */
    public <T> void runInContext(Message message, Class<T> targetType, Consumer<T> consumer) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (message == null || consumer == null) {
            return;
        }
        runInContext(message, msg -> consumer.accept(convertMessage(msg, targetType)));
    }

    /**
     * 在单条消息的四维上下文中反序列化目标类型并执行消费逻辑（同时传入原始消息与领域模型）
     *
     * @param message    单条 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param biConsumer 消费处理逻辑
     * @param <T>        目标 POJO 泛型
     */
    public <T> void runInContext(Message message, Class<T> targetType, BiConsumer<Message, T> biConsumer) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (message == null || biConsumer == null) {
            return;
        }
        runInContext(message, msg -> biConsumer.accept(msg, convertMessage(msg, targetType)));
    }

    /**
     * 在单条消息的四维上下文中反序列化目标类型并执行带返回值处理逻辑
     *
     * @param message    单条 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param function   消费处理函数
     * @param <T>        目标 POJO 泛型
     * @param <R>        返回值泛型
     * @return 执行结果，入参为空时返回 null
     */
    public <T, R> R callInContext(Message message, Class<T> targetType, Function<T, R> function) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (message == null || function == null) {
            return null;
        }
        return callInContext(message, msg -> function.apply(convertMessage(msg, targetType)));
    }

    /**
     * 在单条消息的四维上下文中反序列化目标类型并执行带返回值处理逻辑（同时传入原始消息）
     *
     * @param message    单条 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param biFunction 消费处理函数
     * @param <T>        目标 POJO 泛型
     * @param <R>        返回值泛型
     * @return 执行结果，入参为空时返回 null
     */
    public <T, R> R callInContext(Message message, Class<T> targetType, BiFunction<Message, T, R> biFunction) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (message == null || biFunction == null) {
            return null;
        }
        return callInContext(message, msg -> biFunction.apply(msg, convertMessage(msg, targetType)));
    }

    /**
     * 基于 Inferred Deserialization 批量容错消费强类型 POJO（默认仅记录错误日志并静默跳过幂等冲突）
     *
     * @param messages   批量 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param processor  强类型单条业务处理器
     * @param <T>        目标 POJO 泛型
     */
    public <T> void processBatchSafely(
            Iterable<? extends Message> messages,
            Class<T> targetType,
            Consumer<T> processor) {
        processBatchSafely(messages, targetType, processor, null);
    }

    /**
     * 基于 Inferred Deserialization 批量容错消费强类型 POJO
     *
     * @param messages       批量 AMQP 消息
     * @param targetType     目标领域模型类型
     * @param processor      强类型单条业务处理器
     * @param failureHandler 单条常规异常回调（可为 null；幂等异常不会触发该回调）
     * @param <T>            目标 POJO 泛型
     */
    public <T> void processBatchSafely(
            Iterable<? extends Message> messages,
            Class<T> targetType,
            Consumer<T> processor,
            BiConsumer<Message, Exception> failureHandler) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (messages == null || processor == null) {
            return;
        }
        processBatchSafely(messages, msg -> processor.accept(convertMessage(msg, targetType)), failureHandler);
    }

    /**
     * 基于 Inferred Deserialization 批量容错消费强类型 POJO（双入参：原始消息与领域模型）
     *
     * @param messages   批量 AMQP 消息
     * @param targetType 目标领域模型类型
     * @param processor  双入参单条业务处理器
     * @param <T>        目标 POJO 泛型
     */
    public <T> void processBatchSafely(
            Iterable<? extends Message> messages,
            Class<T> targetType,
            BiConsumer<Message, T> processor) {
        processBatchSafely(messages, targetType, processor, null);
    }

    /**
     * 基于 Inferred Deserialization 批量容错消费强类型 POJO（双入参：原始消息与领域模型）
     *
     * @param messages       批量 AMQP 消息
     * @param targetType     目标领域模型类型
     * @param processor      双入参单条业务处理器
     * @param failureHandler 单条常规异常回调（可为 null；幂等异常不会触发该回调）
     * @param <T>            目标 POJO 泛型
     */
    public <T> void processBatchSafely(
            Iterable<? extends Message> messages,
            Class<T> targetType,
            BiConsumer<Message, T> processor,
            BiConsumer<Message, Exception> failureHandler) {
        Assert.notNull(targetType, "targetType 不能为空");
        if (messages == null || processor == null) {
            return;
        }
        processBatchSafely(messages, msg -> processor.accept(msg, convertMessage(msg, targetType)), failureHandler);
    }

    /**
     * 判断异常或其因果链是否属于幂等重复消费冲突
     *
     * @param throwable 异常实例
     * @return 属于幂等冲突返回 true，否则返回 false
     */
    public static boolean isIdempotentException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            if (current instanceof BusinessException be
                    && CommonResultCode.REPEAT_SUBMIT.getCode().equals(be.getCode())) {
                return true;
            }
            String className = current.getClass().getSimpleName();
            if ("IdempotentException".equals(className) || "DuplicateMessageException".equals(className)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private <T> T convertMessage(Message message, Class<T> targetType) {
        if (targetType.isInstance(message)) {
            return targetType.cast(message);
        }
        Object converted;
        try {
            if (messageConverter instanceof SmartMessageConverter smartConverter) {
                converted = smartConverter.fromMessage(message, ParameterizedTypeReference.forType(targetType));
            } else {
                converted = messageConverter.fromMessage(message);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "批量消息反序列化失败: targetType=" + targetType.getName() + ", reason=" + ex.getMessage(),
                    ex
            );
        }
        if (converted == null) {
            return null;
        }
        if (!targetType.isInstance(converted)) {
            String mismatchMsg = "消息反序列化结果类型不匹配: expected=" + targetType.getName()
                    + ", actual=" + converted.getClass().getName();
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    mismatchMsg,
                    new MessageConversionException(mismatchMsg)
            );
        }
        return targetType.cast(converted);
    }

    private static Map<String, Object> extractHeaders(Message message) {
        MessageProperties props = message.getMessageProperties();
        if (props == null || props.getHeaders() == null) {
            return Collections.emptyMap();
        }
        return props.getHeaders();
    }

    private static void handleMessageFailure(
            Message message,
            Exception ex,
            BiConsumer<Message, Exception> failureHandler) {
        MessageProperties props = message.getMessageProperties();
        Map<String, Object> headers = props != null && props.getHeaders() != null
                ? props.getHeaders()
                : Collections.emptyMap();
        String exchange = props != null ? props.getReceivedExchange() : "";
        String routingKey = props != null ? props.getReceivedRoutingKey() : "";
        String queue = props != null ? props.getConsumerQueue() : "";
        String bizKey = MessageContextPropagator.extractString(headers, MessageHeaderConstants.BIZ_KEY);
        String traceId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.TRACE_ID);

        if (isIdempotentException(ex)) {
            log.warn("[MQ-IDEMPOTENT-DUPLICATE] 批量消费单条检测到幂等防重冲突，静默跳过后续处理: exchange={}, routingKey={}, queue={}, bizKey={}, traceId={}",
                    exchange, routingKey, queue, bizKey, traceId);
            return;
        }

        log.error("[MQ-CONSUME-BATCH] 批量消费单条处理异常，已隔离不影响余下消息: exchange={}, routingKey={}, queue={}, bizKey={}, traceId={}",
                exchange, routingKey, queue, bizKey, traceId, ex);

        if (failureHandler != null) {
            try {
                failureHandler.accept(message, ex);
            } catch (Throwable handlerEx) {
                log.error("[MQ-CONSUME-BATCH] failureHandler 执行次生异常: queue={}, bizKey={}, traceId={}",
                        queue, bizKey, traceId, handlerEx);
            }
        }
    }
}
