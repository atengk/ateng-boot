package io.github.atengk.boot.rocketmq.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.spi.RocketmqPoisonPillCallback;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RocketMQ 消费端声明式监听器反射调用与受控防卫核心执行器
 *
 * <p>实现 RocketMQ 官方 {@link MessageListener} 契约。
 * 在消费生命周期中内聚：
 * 1. 瞬态四维上下文入站还原（MDC traceId、租户、用户）与 {@code finally} 严格还原现场；
 * 2. 入站消息透明 GZIP 解压缩；
 * 3. 微服务推断反序列化（Inferred Type），按方法参数强类型与 {@link java.lang.reflect.ParameterizedType} 精准还原泛型集合；
 * 4. JSR-303 参数校验；
 * 5. 毒丸不可自愈异常判定与安全确认丢弃（记录 {@code [MQ-FATAL-POISON-PILL]} 宏日志，返回 {@link ConsumeResult#SUCCESS}）；
 * 6. 防重幂等冲突静默确认（捕获 {@link IdempotentException}，记录 {@code [MQ-IDEMPOTENT-DUPLICATE]}，返回 {@link ConsumeResult#SUCCESS}）；
 * 7. 瞬态可自愈业务异常触发 Broker 阶梯重试（返回 {@link ConsumeResult#FAILURE}）。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RocketmqListenerInvoker implements MessageListener {

    private static final int MAX_EXCEPTION_MESSAGE_LENGTH = 1024;
    private static final String REASON_GZIP_DECOMPRESS_FAILED = "GZIP_DECOMPRESS_FAILED";
    private static final String REASON_DESERIALIZATION_FAILED = "DESERIALIZATION_FAILED";
    private static final String REASON_VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String REASON_FATAL_EXCEPTION = "FATAL_EXCEPTION";
    private static final Set<String> REPLACED_DIAGNOSTIC_HEADERS = Set.of(
            RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_TOPIC.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_CONSUMER_GROUP.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_MESSAGE_ID.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_REASON.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_CLASS.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_MESSAGE.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_TIMESTAMP.toLowerCase(Locale.ROOT),
            RocketmqConstants.HEADER_DEAD_LETTER_DELIVERY_ATTEMPT.toLowerCase(Locale.ROOT)
    );

    @Getter
    private final Object targetBean;
    @Getter
    private final Method method;
    @Getter
    private final String topic;
    @Getter
    private final String consumerGroup;
    private final Validator validator;
    private final boolean forwardPoisonPillToDlq;
    private final ObjectProvider<Producer> producerProvider;
    private final Producer producer;
    private final ObjectProvider<RocketmqPoisonPillCallback> poisonPillCallbackProvider;
    private final RocketmqPoisonPillCallback poisonPillCallback;
    private final ClientServiceProvider clientServiceProvider = ClientServiceProvider.loadService();
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public RocketmqListenerInvoker(Object targetBean, Method method, String topic, String consumerGroup) {
        this(targetBean, method, topic, consumerGroup, null);
    }

    public RocketmqListenerInvoker(
            Object targetBean,
            Method method,
            String topic,
            String consumerGroup,
            Validator validator) {
        this(targetBean, method, topic, consumerGroup, validator, true, (ObjectProvider<Producer>) null, null);
    }

    public RocketmqListenerInvoker(
            Object targetBean,
            Method method,
            String topic,
            String consumerGroup,
            Validator validator,
            boolean forwardPoisonPillToDlq,
            Producer producer,
            RocketmqPoisonPillCallback poisonPillCallback) {
        this(targetBean, method, topic, consumerGroup, validator, forwardPoisonPillToDlq, null, producer, null, poisonPillCallback);
    }

    public RocketmqListenerInvoker(
            Object targetBean,
            Method method,
            String topic,
            String consumerGroup,
            Validator validator,
            boolean forwardPoisonPillToDlq,
            ObjectProvider<Producer> producerProvider,
            ObjectProvider<RocketmqPoisonPillCallback> poisonPillCallbackProvider) {
        this(targetBean, method, topic, consumerGroup, validator, forwardPoisonPillToDlq, producerProvider, null, poisonPillCallbackProvider, null);
    }

    private RocketmqListenerInvoker(
            Object targetBean,
            Method method,
            String topic,
            String consumerGroup,
            Validator validator,
            boolean forwardPoisonPillToDlq,
            ObjectProvider<Producer> producerProvider,
            Producer producer,
            ObjectProvider<RocketmqPoisonPillCallback> poisonPillCallbackProvider,
            RocketmqPoisonPillCallback poisonPillCallback) {
        this.targetBean = Objects.requireNonNull(targetBean, "targetBean 不能为空");
        this.method = Objects.requireNonNull(method, "method 不能为空");
        this.topic = Objects.requireNonNull(topic, "topic 不能为空");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup 不能为空");
        this.validator = validator;
        this.forwardPoisonPillToDlq = forwardPoisonPillToDlq;
        this.producerProvider = producerProvider;
        this.producer = producer;
        this.poisonPillCallbackProvider = poisonPillCallbackProvider;
        this.poisonPillCallback = poisonPillCallback;
        ReflectionUtils.makeAccessible(this.method);
    }

    /**
     * 判断当前监听端点是否处于暂停状态
     *
     * @return 若处于暂停流控状态返回 true
     */
    public boolean isPaused() {
        return this.paused.get();
    }

    /**
     * 暂停当前监听端点消费
     */
    public void pause() {
        this.paused.set(true);
        log.warn("[ateng-boot-rocketmq] 监听端点已暂停消费: topic={}, consumerGroup={}", topic, consumerGroup);
    }

    /**
     * 恢复当前监听端点消费
     */
    public void resume() {
        this.paused.set(false);
        log.info("[ateng-boot-rocketmq] 监听端点已恢复消费: topic={}, consumerGroup={}", topic, consumerGroup);
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        if (messageView == null) {
            log.warn("[MQ-CONSUME] 收到空 MessageView，直接确认忽略");
            return ConsumeResult.SUCCESS;
        }

        long startTime = System.currentTimeMillis();
        Map<String, String> properties = messageView.getProperties() != null
                ? messageView.getProperties()
                : Collections.emptyMap();

        String tag = messageView.getTag().orElse("");
        String messageId = messageView.getMessageId() != null ? messageView.getMessageId().toString() : "UNKNOWN";

        // 1. 激活四维上下文并确保在 finally 中严格清理
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(properties)) {
            String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);

            // 2. 检查是否处于暂停流控状态
            if (this.paused.get()) {
                log.warn("[MQ-CONSUME-PAUSED] mq=rocketmq topic={} consumerGroup={} tag={} traceId={} messageId={} 监听端点处于暂停状态，返回 FAILURE 触发 Broker 阶梯退避重试",
                        topic, consumerGroup, tag, traceId, messageId);
                return ConsumeResult.FAILURE;
            }

            // 3. 提取 bizKey (优先 properties 约定 -> 原生 keys -> traceId 兜底)
            String bizKey = resolveBizKey(properties, messageView, traceId);

            // 4. 提取报文并透明解压
            byte[] bodyBytes;
            try {
                bodyBytes = extractAndDecompressBody(messageView, properties);
            } catch (Exception ex) {
                return handlePoisonPill(messageView, properties, tag, bizKey, traceId, messageId,
                        REASON_GZIP_DECOMPRESS_FAILED, "消息体 GZIP 解压缩失败", ex);
            }

            int bodySize = bodyBytes.length;

            // 5. 解析监听方法实参 (依据入参声明强类型与泛型集合推断反序列化)
            Object[] args;
            try {
                args = resolveArguments(messageView, bodyBytes);
            } catch (Throwable t) {
                return handlePoisonPill(messageView, properties, tag, bizKey, traceId, messageId,
                        REASON_DESERIALIZATION_FAILED, "消息推断反序列化失败", t);
            }

            // 6. 执行 JSR-303 参数校验
            try {
                validateArguments(args);
            } catch (Throwable t) {
                return handlePoisonPill(messageView, properties, tag, bizKey, traceId, messageId,
                        REASON_VALIDATION_FAILED, "消息参数 JSR-303 校验失败", t);
            }

            // 7. 反射执行业务监听方法
            try {
                method.invoke(targetBean, args);
                long cost = System.currentTimeMillis() - startTime;
                log.info("[MQ-CONSUME] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                        topic, consumerGroup, tag, bizKey, traceId, bodySize, cost, messageId);
                return ConsumeResult.SUCCESS;
            } catch (InvocationTargetException ite) {
                Throwable targetEx = ite.getTargetException();
                return handleException(messageView, properties, targetEx, tag, bizKey, traceId, messageId, bodySize, startTime);
            } catch (Throwable t) {
                return handleException(messageView, properties, t, tag, bizKey, traceId, messageId, bodySize, startTime);
            }
        }
    }

    private ConsumeResult handleException(
            MessageView messageView,
            Map<String, String> properties,
            Throwable ex,
            String tag,
            String bizKey,
            String traceId,
            String messageId,
            int bodySize,
            long startTime) {
        long cost = System.currentTimeMillis() - startTime;

        // 1. 检查是否为防重幂等拦截异常
        if (isIdempotentException(ex)) {
            log.warn("[MQ-IDEMPOTENT-DUPLICATE] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} messageId={} 命中防重幂等拦截，静默确认并阻断重试: {}",
                    topic, consumerGroup, tag, bizKey, traceId, messageId, ex.getMessage());
            return ConsumeResult.SUCCESS;
        }

        // 2. 检查是否为不可自愈致命毒丸异常
        if (FatalExceptionClassifier.isFatal(ex)) {
            return handlePoisonPill(messageView, properties, tag, bizKey, traceId, messageId,
                    REASON_FATAL_EXCEPTION, "业务执行抛出不可自愈致命异常", ex);
        }

        // 3. 常规瞬态业务异常，触发 Broker 服务端重试
        log.error("[MQ-CONSUME] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                topic, consumerGroup, tag, bizKey, traceId, bodySize, cost, ex.getMessage(), ex);
        return ConsumeResult.FAILURE;
    }

    private ConsumeResult handlePoisonPill(
            MessageView messageView,
            Map<String, String> properties,
            String tag,
            String bizKey,
            String traceId,
            String messageId,
            String reason,
            String description,
            Throwable cause) {
        String dlqTopic = RocketmqConstants.DLQ_TOPIC_PREFIX + consumerGroup.trim();
        log.error("[MQ-FATAL-POISON-PILL] mq=rocketmq topic={} consumerGroup={} dlqTopic={} tag={} bizKey={} traceId={} messageId={} reason={} {}，阻断重试: {}",
                topic, consumerGroup, dlqTopic, tag, bizKey, traceId, messageId, reason, description,
                cause != null ? cause.getMessage() : "", cause);

        boolean forwardedToDlq = false;
        if (this.forwardPoisonPillToDlq) {
            forwardedToDlq = tryForwardPoisonPillToDlq(messageView, properties, tag, bizKey, messageId, dlqTopic, reason, cause);
        } else {
            log.warn("[MQ-POISON-PILL-DLQ-DISABLED] mq=rocketmq topic={} consumerGroup={} dlqTopic={} messageId={} reason={} forwardPoisonPillToDlq=false，跳过转储并直接确认",
                    topic, consumerGroup, dlqTopic, messageId, reason);
        }

        invokePoisonPillCallbackSafely(messageView, dlqTopic, forwardedToDlq, cause);
        return ConsumeResult.SUCCESS;
    }

    private boolean tryForwardPoisonPillToDlq(
            MessageView messageView,
            Map<String, String> properties,
            String tag,
            String bizKey,
            String messageId,
            String dlqTopic,
            String reason,
            Throwable cause) {
        Producer activeProducer = resolveProducer();
        if (activeProducer == null) {
            log.warn("[MQ-POISON-PILL-DLQ-SKIP] mq=rocketmq topic={} consumerGroup={} dlqTopic={} messageId={} reason={} 未检测到可选 Producer，跳过死信转储并安全确认",
                    topic, consumerGroup, dlqTopic, messageId, reason);
            return false;
        }

        try {
            Message dlqMessage = buildDlqMessage(messageView, properties, tag, bizKey, messageId, dlqTopic, reason, cause);
            activeProducer.send(dlqMessage);
            log.warn("[MQ-POISON-PILL-DLQ-FORWARDED] mq=rocketmq topic={} consumerGroup={} dlqTopic={} tag={} bizKey={} messageId={} reason={} 毒丸消息已附加诊断头归档至死信主题",
                    topic, consumerGroup, dlqTopic, tag, bizKey, messageId, reason);
            return true;
        } catch (Exception ex) {
            log.error("[MQ-POISON-PILL-DLQ-FAILED] mq=rocketmq topic={} consumerGroup={} dlqTopic={} messageId={} reason={} 毒丸消息转发死信主题失败，已安全确认避免陷入死循环自旋: {}",
                    topic, consumerGroup, dlqTopic, messageId, reason, ex.getMessage(), ex);
            return false;
        }
    }

    private Message buildDlqMessage(
            MessageView messageView,
            Map<String, String> properties,
            String tag,
            String bizKey,
            String messageId,
            String dlqTopic,
            String reason,
            Throwable cause) {
        MessageBuilder builder = clientServiceProvider.newMessageBuilder();
        builder.setTopic(dlqTopic);
        if (StringUtils.hasText(tag)) {
            builder.setTag(tag.trim());
        }

        List<String> keysToKeep = new ArrayList<>();
        if (messageView.getKeys() != null) {
            for (String key : messageView.getKeys()) {
                if (StringUtils.hasText(key)) {
                    keysToKeep.add(key.trim());
                }
            }
        }
        if (keysToKeep.isEmpty() && StringUtils.hasText(bizKey)) {
            keysToKeep.add(bizKey.trim());
        }
        if (!keysToKeep.isEmpty()) {
            builder.setKeys(keysToKeep.toArray(String[]::new));
        }

        if (messageView.getMessageGroup() != null) {
            messageView.getMessageGroup()
                    .filter(StringUtils::hasText)
                    .ifPresent(group -> builder.setMessageGroup(group.trim()));
        }

        builder.setBody(readRawBody(messageView.getBody()));

        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (!StringUtils.hasText(key) || val == null) {
                    continue;
                }
                String trimmedKey = key.trim();
                String lowerKey = trimmedKey.toLowerCase(Locale.ROOT);
                if (lowerKey.startsWith("__") || lowerKey.startsWith("%retry%") || lowerKey.startsWith("%dlq%")) {
                    continue;
                }
                if (REPLACED_DIAGNOSTIC_HEADERS.contains(lowerKey)) {
                    continue;
                }
                builder.addProperty(trimmedKey, val);
            }
        }

        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_TOPIC, topic);
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_CONSUMER_GROUP, consumerGroup);
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_ORIGINAL_MESSAGE_ID, messageId);
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_REASON, reason);
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_CLASS,
                cause != null ? cause.getClass().getName() : "");
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_EXCEPTION_MESSAGE,
                truncateExceptionMessage(cause));
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_TIMESTAMP,
                String.valueOf(System.currentTimeMillis()));
        builder.addProperty(RocketmqConstants.HEADER_DEAD_LETTER_DELIVERY_ATTEMPT,
                String.valueOf(Math.max(1, messageView.getDeliveryAttempt())));

        return builder.build();
    }

    private void invokePoisonPillCallbackSafely(
            MessageView messageView,
            String dlqTopic,
            boolean forwardedToDlq,
            Throwable cause) {
        RocketmqPoisonPillCallback callback = resolvePoisonPillCallback();
        if (callback == null) {
            return;
        }
        try {
            callback.onPoisonPill(messageView, topic, consumerGroup, dlqTopic, forwardedToDlq, cause);
        } catch (Exception callbackEx) {
            log.error("[MQ-POISON-PILL-CALLBACK-FAILED] mq=rocketmq topic={} consumerGroup={} dlqTopic={} 毒丸回调 SPI 执行异常已被隔离: {}",
                    topic, consumerGroup, dlqTopic, callbackEx.getMessage(), callbackEx);
        }
    }

    private Producer resolveProducer() {
        if (producerProvider != null) {
            Producer provided = producerProvider.getIfAvailable();
            if (provided != null) {
                return provided;
            }
        }
        return producer;
    }

    private RocketmqPoisonPillCallback resolvePoisonPillCallback() {
        if (poisonPillCallbackProvider != null) {
            RocketmqPoisonPillCallback provided = poisonPillCallbackProvider.getIfAvailable();
            if (provided != null) {
                return provided;
            }
        }
        return poisonPillCallback;
    }

    private byte[] readRawBody(ByteBuffer byteBuffer) {
        if (byteBuffer == null || byteBuffer.remaining() == 0) {
            return new byte[0];
        }
        byte[] rawBytes = new byte[byteBuffer.remaining()];
        byteBuffer.asReadOnlyBuffer().get(rawBytes);
        return rawBytes;
    }

    private String truncateExceptionMessage(Throwable cause) {
        if (cause == null) {
            return "";
        }
        String msg = cause.getMessage();
        if (!StringUtils.hasText(msg)) {
            msg = cause.getClass().getSimpleName();
        }
        if (msg.length() > MAX_EXCEPTION_MESSAGE_LENGTH) {
            return msg.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH);
        }
        return msg;
    }

    private String resolveBizKey(Map<String, String> properties, MessageView messageView, String traceId) {
        String bizKey = properties.get(MessageHeaderConstants.BIZ_KEY);
        if (bizKey != null && !bizKey.isBlank()) {
            return bizKey;
        }
        bizKey = properties.get("X-Biz-Key");
        if (bizKey != null && !bizKey.isBlank()) {
            return bizKey;
        }
        if (messageView.getKeys() != null && !messageView.getKeys().isEmpty()) {
            String firstKey = messageView.getKeys().iterator().next();
            if (firstKey != null && !firstKey.isBlank()) {
                return firstKey;
            }
        }
        return traceId != null ? traceId : "";
    }

    private byte[] extractAndDecompressBody(MessageView messageView, Map<String, String> properties) throws IOException {
        byte[] bodyBytes = readRawBody(messageView.getBody());

        String compression = properties.get(RocketmqConstants.HEADER_COMPRESSION);
        if (RocketmqConstants.COMPRESSION_GZIP.equalsIgnoreCase(compression)) {
            return RocketmqMessageHelper.decompressGzip(bodyBytes);
        }
        return bodyBytes;
    }

    private Object[] resolveArguments(MessageView messageView, byte[] bodyBytes) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();
            if (MessageView.class.isAssignableFrom(paramType)) {
                args[i] = messageView;
            } else if (byte[].class.equals(paramType)) {
                args[i] = bodyBytes;
            } else if (String.class.equals(paramType)) {
                args[i] = new String(bodyBytes, StandardCharsets.UTF_8);
            } else {
                Type genericType = param.getParameterizedType();
                args[i] = JsonUtil.parseObject(bodyBytes, genericType);
            }
        }
        return args;
    }

    private void validateArguments(Object[] args) {
        if (validator == null || args == null) {
            return;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            if (param.isAnnotationPresent(Valid.class) || hasValidatedAnnotation(param)) {
                Object arg = args[i];
                if (arg != null) {
                    Set<ConstraintViolation<Object>> violations = validator.validate(arg);
                    if (!violations.isEmpty()) {
                        throw new ConstraintViolationException(violations);
                    }
                }
            }
        }
    }

    private boolean hasValidatedAnnotation(Parameter parameter) {
        for (Annotation ann : parameter.getAnnotations()) {
            if ("Validated".equals(ann.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isIdempotentException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            if ("io.github.atengk.boot.core.exception.IdempotentException".equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
