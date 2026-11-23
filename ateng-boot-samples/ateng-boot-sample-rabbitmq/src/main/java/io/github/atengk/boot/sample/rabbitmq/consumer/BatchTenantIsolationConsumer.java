package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.rabbitmq.executor.RabbitBatchExecutor;
import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批量多租户上下文隔离与单条容错演示消费者
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchTenantIsolationConsumer {

    private final RabbitBatchExecutor rabbitBatchExecutor;

    private final CopyOnWriteArrayList<TenantConsumeRecord> successRecords = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TenantFailureRecord> failedRecords = new CopyOnWriteArrayList<>();

    /**
     * 批量接收带租户头信息的订单消息并委托 {@link RabbitBatchExecutor} 逐条隔离处理
     *
     * @param messages 原始 AMQP 批量消息集合
     */
    @RabbitListener(
            queues = RabbitScenarioTopologyConfig.QUEUE_BATCH_TENANT,
            containerFactory = "batchRabbitListenerContainerFactory"
    )
    public void onBatchMessages(List<Message> messages) {
        log.info("[BATCH-TENANT-CONSUMER] 接收批量租户混排消息: count={}", messages != null ? messages.size() : 0);
        rabbitBatchExecutor.processBatchSafely(
                messages,
                OrderCreateMessage.class,
                this::handleSingleOrder,
                this::handleFailedOrder
        );
    }

    /**
     * 获取处理成功的租户隔离消费记录快照
     *
     * @return 成功消费记录只读列表
     */
    public List<TenantConsumeRecord> getSuccessRecords() {
        return List.copyOf(successRecords);
    }

    /**
     * 获取异常容错捕获到的失败消费记录快照
     *
     * @return 失败消费记录只读列表
     */
    public List<TenantFailureRecord> getFailedRecords() {
        return List.copyOf(failedRecords);
    }

    /**
     * 获取多租户批量消费审计视图（包含成功与失败明细）
     *
     * @return 审计视图
     */
    public BatchTenantRecordsView getAuditView() {
        return new BatchTenantRecordsView(getSuccessRecords(), getFailedRecords());
    }

    /**
     * 清空内存演示审计记录
     */
    public void clear() {
        successRecords.clear();
        failedRecords.clear();
    }

    private void handleSingleOrder(OrderCreateMessage order) {
        if (order == null || order.orderId() == null) {
            return;
        }
        if (order.orderId().startsWith("DUP-")) {
            throw new IdempotentException("重复订单幂等拦截: " + order.orderId());
        }
        if (order.orderId().startsWith("FAIL-")) {
            throw new IllegalStateException("模拟订单消费异常: " + order.orderId());
        }
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.get();
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        String traceId = MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY);
        log.info("[BATCH-TENANT-CONSUMER] 逐条处理订单成功: orderId={}, tenantId={}, userId={}, ignoreTenant={}, traceId={}",
                order.orderId(), tenantId, userId, ignoreTenant, traceId);
        successRecords.add(new TenantConsumeRecord(
                order.orderId(),
                order.amount(),
                tenantId,
                userId,
                ignoreTenant,
                traceId
        ));
    }

    private void handleFailedOrder(Message failedMessage, Exception cause) {
        Map<String, Object> headers = failedMessage != null && failedMessage.getMessageProperties() != null
                ? failedMessage.getMessageProperties().getHeaders()
                : Map.of();
        String orderId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.BIZ_KEY);
        String tenantId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.TENANT_ID);
        String reason = cause != null ? cause.getMessage() : "unknown";
        log.warn("[BATCH-TENANT-CONSUMER] 记录单条消费异常兜底明细: orderId={}, tenantId={}, reason={}",
                orderId, tenantId, reason);
        failedRecords.add(new TenantFailureRecord(orderId, tenantId, reason));
    }

    /**
     * 单条租户隔离消费成功审计记录
     */
    public record TenantConsumeRecord(
            String orderId,
            BigDecimal amount,
            String tenantId,
            String userId,
            boolean ignoreTenant,
            String traceId
    ) {
    }

    /**
     * 单条租户隔离消费异常审计记录
     */
    public record TenantFailureRecord(
            String orderId,
            String tenantId,
            String reason
    ) {
    }

    /**
     * 批量多租户消费结果查询视图
     */
    public record BatchTenantRecordsView(
            List<TenantConsumeRecord> successRecords,
            List<TenantFailureRecord> failedRecords
    ) {
    }
}
