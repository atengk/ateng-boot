package io.github.atengk.boot.rabbitmq.interceptor;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * RabbitMQ 消费端全局环绕拦截链
 * 负责四维上下文入站透明激活与 finally 确定性清理、@Idempotent 幂等冲突静默确认 (Silent ACK) 与 [MQ-CONSUME] 结构化宏日志
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RabbitListenerGlobalAdvice implements MethodInterceptor {

    private final RabbitmqProperties properties;

    public RabbitListenerGlobalAdvice(RabbitmqProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = extractMessage(invocation);
        if (message == null) {
            return invocation.proceed();
        }

        MessageProperties props = message.getMessageProperties();
        Map<String, Object> headers = props != null ? props.getHeaders() : Collections.emptyMap();
        String exchange = props != null ? props.getReceivedExchange() : "";
        String routingKey = props != null ? props.getReceivedRoutingKey() : "";
        String queue = props != null ? props.getConsumerQueue() : "";
        String bizKey = MessageContextPropagator.extractString(headers, MessageHeaderConstants.BIZ_KEY);
        String traceId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.TRACE_ID);

        // 1. 入站安全激活四维上下文 (TraceId, TenantId, UserId, IgnoreTenant)，并在 finally 块中绝对可靠恢复现场
        try (MessageContextPropagator.ContextScope scope = MessageContextPropagator.activate(headers)) {
            long start = System.currentTimeMillis();
            try {
                Object result = invocation.proceed();
                long cost = System.currentTimeMillis() - start;
                logMacroConsume(exchange, routingKey, bizKey, traceId, "SUCCESS", cost, null);
                return result;
            } catch (Throwable t) {
                // 2. 拦截幂等防重冲突：记录警告日志并返回 null，触发 AUTO 模式下的静默 ACK，阻断无休止重试风暴
                if (isIdempotentException(t)) {
                    long cost = System.currentTimeMillis() - start;
                    log.warn("[MQ-IDEMPOTENT-DUPLICATE] 消费端检测到幂等防重冲突，触发静默确认 (Silent ACK): exchange={}, routingKey={}, queue={}, bizKey={}, traceId={}",
                            exchange, routingKey, queue, bizKey, traceId);
                    logMacroConsume(exchange, routingKey, bizKey, traceId, "SUCCESS", cost, "Idempotent duplicate");
                    return null;
                }

                // 3. 记录失败消费耗时与状态 (FATAL_DLQ 或 RETRY)
                long cost = System.currentTimeMillis() - start;
                boolean isFatal = FatalExceptionClassifier.isFatal(t);
                String status = isFatal ? "FATAL_DLQ" : "RETRY";
                logMacroConsume(exchange, routingKey, bizKey, traceId, status, cost, t.getMessage());
                throw t;
            }
        }
    }

    /**
     * 从方法调用入参中提取 AMQP Message 对象
     */
    private Message extractMessage(MethodInvocation invocation) {
        Object[] args = invocation.getArguments();
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Message msg) {
                    return msg;
                }
            }
        }
        return null;
    }

    /**
     * 判断异常或其因果链是否属于幂等防重异常
     */
    private boolean isIdempotentException(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            if (current instanceof BusinessException be && CommonResultCode.REPEAT_SUBMIT.getCode().equals(be.getCode())) {
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

    /**
     * 输出结构化入站消费审计宏日志
     */
    private void logMacroConsume(
            String exchange,
            String routingKey,
            String bizKey,
            String traceId,
            String status,
            long cost,
            String error) {
        if (!properties.getLogging().isEnabled()) {
            return;
        }
        String prevTraceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, traceId);
            }
            if ("SUCCESS".equals(status)) {
                log.info("[MQ-CONSUME] mq=rabbitmq topic={} bizKey={} traceId={} status=SUCCESS cost={}ms",
                        exchange, bizKey, traceId, cost);
            } else if ("RETRY".equals(status)) {
                log.warn("[MQ-CONSUME] mq=rabbitmq topic={} bizKey={} traceId={} status=RETRY cost={}ms error={}",
                        exchange, bizKey, traceId, cost, error != null ? error : "Unknown");
            } else {
                log.error("[MQ-CONSUME] mq=rabbitmq topic={} bizKey={} traceId={} status=FATAL_DLQ cost={}ms error={}",
                        exchange, bizKey, traceId, cost, error != null ? error : "Unknown");
            }
        } finally {
            if (prevTraceId != null) {
                MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, prevTraceId);
            } else {
                MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
            }
        }
    }
}
