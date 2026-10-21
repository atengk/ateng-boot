package io.github.atengk.boot.kafka.interceptor;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 单条消费入站上下文拦截器
 *
 * <p>负责在单条消息被消费前提取并解码 UTF-8 Headers，激活四维上下文（MDC TraceId、User 身份、Tenant 租户与 IgnoreTenant 标志位），
 * 提取并补齐 {@code bizKey}，并在消费结束时在 {@code finally} 中严格清理现场并输出 {@code [MQ-CONSUME]} 结构化日志。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class KafkaRecordInterceptor implements RecordInterceptor<Object, Object> {

    private static final ThreadLocal<Deque<ConsumeContext>> CONTEXT_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        return intercept(record);
    }

    /**
     * 单记录拦截处理
     *
     * @param record 待拦截记录
     * @return 处理后的记录
     */
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record) {
        if (record == null) {
            return null;
        }

        // 1. 提取并解码所有 Headers 转换为 Map
        Map<String, Object> headerMap = new HashMap<>();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                if (header != null && header.key() != null) {
                    headerMap.put(header.key(), header.value());
                }
            }
        }

        // 2. 提取并补齐 bizKey
        String bizKey = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.BIZ_KEY);
        if (bizKey == null || bizKey.isBlank()) {
            if (record.key() != null) {
                bizKey = String.valueOf(record.key());
            } else {
                String traceId = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.TRACE_ID);
                bizKey = (traceId != null && !traceId.isBlank()) ? traceId : IdUtil.fastSimpleUUID();
            }
            if (record.headers() != null) {
                record.headers().add(new RecordHeader(MessageHeaderConstants.BIZ_KEY, bizKey.getBytes(StandardCharsets.UTF_8)));
            }
            headerMap.put(MessageHeaderConstants.BIZ_KEY, bizKey);
        }

        // 3. 激活四维上下文
        MessageContextPropagator.ContextScope scope = MessageContextPropagator.activate(headerMap);
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = MessageContextPropagator.extractString(headerMap, MessageHeaderConstants.TRACE_ID);
        }

        // 4. 入栈当前消费上下文信息
        ConsumeContext ctx = new ConsumeContext(scope, System.currentTimeMillis(), bizKey, traceId);
        pushContext(ctx);

        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        ConsumeContext ctx = peekContext();
        if (ctx != null) {
            ctx.failed = false;
        }
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
        ConsumeContext ctx = peekContext();
        if (ctx != null) {
            ctx.failed = true;
            ctx.errorMessage = (exception != null) ? exception.getMessage() : "Unknown consumer error";
        }
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        ConsumeContext ctx = popContext();
        if (ctx == null) {
            return;
        }

        try {
            long cost = Math.max(0, System.currentTimeMillis() - ctx.startTime);
            if (ctx.failed) {
                log.error("{} mq=kafka topic={} partition={} offset={} bizKey={} traceId={} cost={}ms status=FAILED error={}",
                        KafkaConstants.LOG_CONSUME_PREFIX,
                        record != null ? record.topic() : "UNKNOWN",
                        record != null ? record.partition() : -1,
                        record != null ? record.offset() : -1,
                        ctx.bizKey,
                        ctx.traceId,
                        cost,
                        ctx.errorMessage);
            } else {
                log.info("{} mq=kafka topic={} partition={} offset={} bizKey={} traceId={} cost={}ms status=SUCCESS",
                        KafkaConstants.LOG_CONSUME_PREFIX,
                        record != null ? record.topic() : "UNKNOWN",
                        record != null ? record.partition() : -1,
                        record != null ? record.offset() : -1,
                        ctx.bizKey,
                        ctx.traceId,
                        cost);
            }
        } finally {
            try {
                if (ctx.scope != null) {
                    ctx.scope.close();
                }
            } finally {
                Deque<ConsumeContext> deque = CONTEXT_STACK.get();
                if (deque == null || deque.isEmpty()) {
                    CONTEXT_STACK.remove();
                }
            }
        }
    }

    private static void pushContext(ConsumeContext ctx) {
        CONTEXT_STACK.get().push(ctx);
    }

    private static ConsumeContext peekContext() {
        Deque<ConsumeContext> deque = CONTEXT_STACK.get();
        return (deque != null && !deque.isEmpty()) ? deque.peek() : null;
    }

    private static ConsumeContext popContext() {
        Deque<ConsumeContext> deque = CONTEXT_STACK.get();
        return (deque != null && !deque.isEmpty()) ? deque.pop() : null;
    }

    /**
     * 单条记录消费上下文
     */
    private static final class ConsumeContext {
        private final MessageContextPropagator.ContextScope scope;
        private final long startTime;
        private final String bizKey;
        private final String traceId;
        private boolean failed;
        private String errorMessage;

        ConsumeContext(MessageContextPropagator.ContextScope scope, long startTime, String bizKey, String traceId) {
            this.scope = scope;
            this.startTime = startTime;
            this.bizKey = bizKey;
            this.traceId = traceId;
        }
    }
}
