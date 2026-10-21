package io.github.atengk.boot.kafka.interceptor;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.kafka.constant.KafkaConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.MDC;
import org.springframework.kafka.listener.BatchInterceptor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Kafka 批量消费入站拦截器
 *
 * <p>负责在拉取到批量记录时生成批次全局 TraceId，统计批次消费耗时，
 * 并在批次结束时清理批次级现场与输出 {@code [MQ-CONSUME-BATCH]} 结构化日志。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class KafkaBatchInterceptor implements BatchInterceptor<Object, Object> {

    private static final ThreadLocal<Deque<BatchContext>> CONTEXT_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public ConsumerRecords<Object, Object> intercept(ConsumerRecords<Object, Object> records, Consumer<Object, Object> consumer) {
        if (records == null || records.isEmpty()) {
            return records;
        }

        String batchTraceId = "batch-" + IdUtil.fastSimpleUUID();
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, batchTraceId);

        BatchContext ctx = new BatchContext(System.currentTimeMillis(), batchTraceId, records.count());
        pushContext(ctx);

        return records;
    }

    @Override
    public void success(ConsumerRecords<Object, Object> records, Consumer<Object, Object> consumer) {
        BatchContext ctx = popContext();
        if (ctx == null) {
            MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
            return;
        }

        try {
            long cost = Math.max(0, System.currentTimeMillis() - ctx.startTime);
            log.info("{} mq=kafka batchTraceId={} count={} cost={}ms status=SUCCESS",
                    KafkaConstants.LOG_CONSUME_BATCH_PREFIX,
                    ctx.batchTraceId,
                    ctx.count,
                    cost);
        } finally {
            cleanupMdcAndStack();
        }
    }

    @Override
    public void failure(ConsumerRecords<Object, Object> records, Exception exception, Consumer<Object, Object> consumer) {
        BatchContext ctx = popContext();
        String errorMsg = (exception != null) ? exception.getMessage() : "Unknown batch error";
        if (ctx == null) {
            MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
            return;
        }

        try {
            long cost = Math.max(0, System.currentTimeMillis() - ctx.startTime);
            log.error("{} mq=kafka batchTraceId={} count={} cost={}ms status=FAILED error={}",
                    KafkaConstants.LOG_CONSUME_BATCH_PREFIX,
                    ctx.batchTraceId,
                    ctx.count,
                    cost,
                    errorMsg);
        } finally {
            cleanupMdcAndStack();
        }
    }

    private static void cleanupMdcAndStack() {
        try {
            MDC.remove(MessageContextPropagator.MDC_TRACE_ID_KEY);
        } finally {
            Deque<BatchContext> deque = CONTEXT_STACK.get();
            if (deque == null || deque.isEmpty()) {
                CONTEXT_STACK.remove();
            }
        }
    }

    private static void pushContext(BatchContext ctx) {
        CONTEXT_STACK.get().push(ctx);
    }

    private static BatchContext popContext() {
        Deque<BatchContext> deque = CONTEXT_STACK.get();
        return (deque != null && !deque.isEmpty()) ? deque.pop() : null;
    }

    /**
     * 批次消费上下文
     */
    private static final class BatchContext {
        private final long startTime;
        private final String batchTraceId;
        private final int count;

        BatchContext(long startTime, String batchTraceId, int count) {
            this.startTime = startTime;
            this.batchTraceId = batchTraceId;
            this.count = count;
        }
    }
}
