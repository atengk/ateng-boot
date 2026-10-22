package io.github.atengk.boot.rocketmq.listener;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.constant.RocketmqConstants;
import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.MDC;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    @Getter
    private final Object targetBean;
    @Getter
    private final Method method;
    @Getter
    private final String topic;
    @Getter
    private final String consumerGroup;
    private final Validator validator;

    public RocketmqListenerInvoker(Object targetBean, Method method, String topic, String consumerGroup) {
        this(targetBean, method, topic, consumerGroup, null);
    }

    public RocketmqListenerInvoker(
            Object targetBean,
            Method method,
            String topic,
            String consumerGroup,
            Validator validator) {
        this.targetBean = Objects.requireNonNull(targetBean, "targetBean 不能为空");
        this.method = Objects.requireNonNull(method, "method 不能为空");
        this.topic = Objects.requireNonNull(topic, "topic 不能为空");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup 不能为空");
        this.validator = validator;
        ReflectionUtils.makeAccessible(this.method);
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

            // 2. 提取 bizKey (优先 properties 约定 -> 原生 keys -> traceId 兜底)
            String bizKey = resolveBizKey(properties, messageView, traceId);

            // 3. 提取报文并透明解压
            byte[] bodyBytes;
            try {
                bodyBytes = extractAndDecompressBody(messageView, properties);
            } catch (Exception ex) {
                log.error("[MQ-FATAL-POISON-PILL] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} messageId={} 消息体 GZIP 解压缩失败，丢弃毒丸: {}",
                        topic, consumerGroup, tag, bizKey, traceId, messageId, ex.getMessage(), ex);
                return ConsumeResult.SUCCESS;
            }

            int bodySize = bodyBytes.length;

            // 4. 解析监听方法实参 (依据入参声明强类型与泛型集合推断反序列化)
            Object[] args;
            try {
                args = resolveArguments(messageView, bodyBytes);
            } catch (Throwable t) {
                log.error("[MQ-FATAL-POISON-PILL] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} messageId={} 消息推断反序列化失败，丢弃毒丸: {}",
                        topic, consumerGroup, tag, bizKey, traceId, messageId, t.getMessage(), t);
                return ConsumeResult.SUCCESS;
            }

            // 5. 执行 JSR-303 参数校验
            try {
                validateArguments(args);
            } catch (Throwable t) {
                log.error("[MQ-FATAL-POISON-PILL] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} messageId={} 消息参数 JSR-303 校验失败，丢弃毒丸: {}",
                        topic, consumerGroup, tag, bizKey, traceId, messageId, t.getMessage(), t);
                return ConsumeResult.SUCCESS;
            }

            // 6. 反射执行业务监听方法
            try {
                method.invoke(targetBean, args);
                long cost = System.currentTimeMillis() - startTime;
                log.info("[MQ-CONSUME] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} size={}B status=SUCCESS cost={}ms messageId={}",
                        topic, consumerGroup, tag, bizKey, traceId, bodySize, cost, messageId);
                return ConsumeResult.SUCCESS;
            } catch (InvocationTargetException ite) {
                Throwable targetEx = ite.getTargetException();
                return handleException(targetEx, tag, bizKey, traceId, messageId, bodySize, startTime);
            } catch (Throwable t) {
                return handleException(t, tag, bizKey, traceId, messageId, bodySize, startTime);
            }
        }
    }

    private ConsumeResult handleException(
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
            log.error("[MQ-FATAL-POISON-PILL] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} messageId={} 业务执行抛出不可自愈致命异常，丢弃毒丸: {}",
                    topic, consumerGroup, tag, bizKey, traceId, messageId, ex.getMessage(), ex);
            return ConsumeResult.SUCCESS;
        }

        // 3. 常规瞬态业务异常，触发 Broker 服务端重试
        log.error("[MQ-CONSUME] mq=rocketmq topic={} consumerGroup={} tag={} bizKey={} traceId={} size={}B status=FAILED cost={}ms error={}",
                topic, consumerGroup, tag, bizKey, traceId, bodySize, cost, ex.getMessage(), ex);
        return ConsumeResult.FAILURE;
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
        ByteBuffer byteBuffer = messageView.getBody();
        byte[] bodyBytes = new byte[byteBuffer.remaining()];
        byteBuffer.slice().get(bodyBytes);

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
