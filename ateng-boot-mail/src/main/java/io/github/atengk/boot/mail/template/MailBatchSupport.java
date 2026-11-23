package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.mail.event.MailSendingEvent;
import io.github.atengk.boot.mail.event.MailSentEvent;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.metrics.MailSendOutcome;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.mail.model.MailSendResultItem;
import io.github.atengk.boot.mail.model.TrackableMimeMessage;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 批量邮件分片背压执行与片内物理 Transport 连接复用引擎
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
final class MailBatchSupport {

    private static final int STATE_PENDING = 0;
    private static final int STATE_CLAIMED = 1;
    private static final int STATE_FINISHED = 2;

    private MailBatchSupport() {
    }

    static BatchMailSendResult executeBatch(MailTemplate template, MailAccount explicitBoundAccount, List<MailMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return BatchMailSendResult.builder()
                    .totalCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .duration(Duration.ZERO)
                    .items(Collections.emptyList())
                    .build();
        }

        long batchStartNanos = System.nanoTime();
        int total = messages.size();
        int concurrency = template.resolveBatchConcurrency();
        int sliceSize = template.resolveBatchSliceSize();
        boolean reuseTransport = template.isBatchReuseTransport();
        Duration batchTimeout = template.resolveBatchTimeout();
        long deadlineNanos = batchStartNanos + batchTimeout.toNanos();

        AtomicReferenceArray<MailSendResultItem> results = new AtomicReferenceArray<>(total);
        AtomicIntegerArray itemStates = new AtomicIntegerArray(total);
        AtomicBoolean batchTimedOut = new AtomicBoolean(false);
        List<BatchItem> pendingItems = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            MailMessage msg = messages.get(i);
            if (msg == null) {
                itemStates.set(i, STATE_FINISHED);
                IllegalArgumentException nullEx = new IllegalArgumentException("MailMessage at index " + i + " must not be null");
                completeBatchItem(results, i, MailSendResultItem.failure(i, null, nullEx.getMessage(), nullEx),
                        template, resolveBatchAccountLabel(template, explicitBoundAccount, null),
                        MailSendOutcome.FAILURE, batchStartNanos);
            } else {
                pendingItems.add(new BatchItem(i, msg));
            }
        }

        List<List<BatchItem>> slices = BatchSliceUtil.slice(pendingItems, sliceSize);
        Semaphore concurrencySemaphore = new Semaphore(concurrency);
        Executor batchExecutor = template.getExecutor() != null ? template.getExecutor() : Runnable::run;
        List<FutureTask<Void>> sliceTasks = new ArrayList<>(slices.size());

        for (List<BatchItem> slice : slices) {
            if (batchTimedOut.get() || Thread.currentThread().isInterrupted()) {
                break;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                batchTimedOut.set(true);
                break;
            }
            boolean permitAcquired;
            try {
                permitAcquired = concurrencySemaphore.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                markSliceInterrupted(template, explicitBoundAccount, slice, itemStates, results, ie, batchStartNanos);
                break;
            }
            if (!permitAcquired) {
                batchTimedOut.set(true);
                break;
            }

            try {
                Runnable taskBody = AsyncContext.wrap(() -> {
                    try {
                        executeSlice(template, explicitBoundAccount, slice, reuseTransport, batchTimedOut, itemStates, results);
                    } finally {
                        concurrencySemaphore.release();
                    }
                });
                FutureTask<Void> sliceTask = new FutureTask<>(taskBody, null);
                sliceTasks.add(sliceTask);
                batchExecutor.execute(sliceTask);
            } catch (Throwable submitEx) {
                concurrencySemaphore.release();
                for (BatchItem item : slice) {
                    if (itemStates.compareAndSet(item.index(), STATE_PENDING, STATE_FINISHED)) {
                        closeQuietly(item.message());
                    }
                    completeBatchItem(results, item.index(),
                            MailSendResultItem.failure(item.index(), item.message(),
                                    "批量分片任务提交失败: " + submitEx.getMessage(), submitEx),
                            template, resolveBatchAccountLabel(template, explicitBoundAccount, item.message()),
                            MailSendOutcome.FAILURE, batchStartNanos);
                }
            }
        }

        awaitSlicesWithTimeout(sliceTasks, deadlineNanos, batchTimedOut);

        // Fail-Safe 收口：补齐超时/取消或未完成条目；仅关闭尚未被 Worker 认领的条目，在途条目由被中断 Worker 自主在 finally 中关闭
        for (int i = 0; i < total; i++) {
            MailMessage msg = messages.get(i);
            if (results.get(i) == null) {
                String errorMsg = batchTimedOut.get()
                        ? "批量邮件发送触发总超时 (" + batchTimeout + ")，已按 Fail-Safe 中止"
                        : "批量发送任务被取消或未获取到执行结果";
                TimeoutException timeoutEx = batchTimedOut.get()
                        ? new TimeoutException(errorMsg)
                        : null;
                completeBatchItem(results, i, MailSendResultItem.failure(i, msg, errorMsg, timeoutEx),
                        template, resolveBatchAccountLabel(template, explicitBoundAccount, msg),
                        MailSendOutcome.FAILURE, batchStartNanos);
            }
            if (itemStates.compareAndSet(i, STATE_PENDING, STATE_FINISHED)) {
                closeQuietly(msg);
            }
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - batchStartNanos);
        List<MailSendResultItem> itemList = new ArrayList<>(total);
        int successCount = 0;
        int failureCount = 0;
        for (int i = 0; i < total; i++) {
            MailSendResultItem item = results.get(i);
            itemList.add(item);
            if (item != null && item.isSuccess()) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        log.info("Batch mail merge completed in {} ms (sliceSize={}, reuseTransport={}): total={}, success={}, failure={}",
                duration.toMillis(), sliceSize, reuseTransport, total, successCount, failureCount);

        return BatchMailSendResult.builder()
                .totalCount(total)
                .successCount(successCount)
                .failureCount(failureCount)
                .duration(duration)
                .items(itemList)
                .build();
    }

    private static void awaitSlicesWithTimeout(List<FutureTask<Void>> sliceTasks,
                                               long deadlineNanos,
                                               AtomicBoolean batchTimedOut) {
        if (sliceTasks.isEmpty()) {
            return;
        }
        if (batchTimedOut.get()) {
            cancelPendingSlices(sliceTasks);
            return;
        }
        for (FutureTask<Void> task : sliceTasks) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                if (!task.isDone()) {
                    batchTimedOut.set(true);
                    cancelPendingSlices(sliceTasks);
                    return;
                }
                continue;
            }
            try {
                task.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                batchTimedOut.set(true);
                cancelPendingSlices(sliceTasks);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cancelPendingSlices(sliceTasks);
                return;
            } catch (ExecutionException | CancellationException ignored) {
                // 单个切片异常已在内部按 Fail-Safe 记录至 results
            }
        }
    }

    private static void cancelPendingSlices(List<FutureTask<Void>> sliceTasks) {
        for (FutureTask<Void> task : sliceTasks) {
            if (!task.isDone()) {
                task.cancel(true);
            }
        }
    }

    private static void markSliceInterrupted(MailTemplate template,
                                             MailAccount explicitBoundAccount,
                                             List<BatchItem> slice,
                                             AtomicIntegerArray itemStates,
                                             AtomicReferenceArray<MailSendResultItem> results,
                                             InterruptedException ie,
                                             long batchStartNanos) {
        for (BatchItem item : slice) {
            if (itemStates.compareAndSet(item.index(), STATE_PENDING, STATE_FINISHED)) {
                closeQuietly(item.message());
            }
            completeBatchItem(results, item.index(),
                    MailSendResultItem.failure(item.index(), item.message(), "批量发送任务被中断: " + ie.getMessage(), ie),
                    template, resolveBatchAccountLabel(template, explicitBoundAccount, item.message()),
                    MailSendOutcome.FAILURE, batchStartNanos);
        }
    }

    private static void executeSlice(MailTemplate template,
                                     MailAccount explicitBoundAccount,
                                     List<BatchItem> slice,
                                     boolean reuseTransport,
                                     AtomicBoolean batchTimedOut,
                                     AtomicIntegerArray itemStates,
                                     AtomicReferenceArray<MailSendResultItem> results) {
        if (!reuseTransport) {
            executeSliceWithoutTransportReuse(template, explicitBoundAccount, slice, batchTimedOut, itemStates, results);
            return;
        }

        // 1. 逐封执行独立前置校验、archiveBcc、容量上限、拦截器 chain 及 logOnly 模拟分流，并按拦截后最终路由账号分组
        Map<TransportGroupKey, List<PreparedBatchEntry>> physicalGroups = new LinkedHashMap<>();

        try {
            for (BatchItem item : slice) {
                if (batchTimedOut.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (!itemStates.compareAndSet(item.index(), STATE_PENDING, STATE_CLAIMED)) {
                    continue;
                }
                MailMessage message = item.message();
                String maskedTo = template.maskRecipients(message);
                long entryStartNanos = System.nanoTime();
                try {
                    MailTemplate.PreparedDelivery prepared = template.prepareForDelivery(explicitBoundAccount, message);
                    if (prepared.logOnly()) {
                        try {
                            MailSendResult sandboxResult = template.deliverSandboxLogOnly(prepared);
                            completeBatchItem(results, item.index(),
                                    MailSendResultItem.success(item.index(), message, sandboxResult),
                                    template, prepared.account().getName(), MailSendOutcome.SANDBOX, entryStartNanos);
                        } finally {
                            itemStates.set(item.index(), STATE_FINISHED);
                            closeQuietly(message);
                        }
                        continue;
                    }
                    TransportGroupKey groupKey = new TransportGroupKey(prepared.account(), prepared.account().getSender());
                    physicalGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                            .add(new PreparedBatchEntry(item.index(), prepared, entryStartNanos));
                } catch (Throwable t) {
                    try {
                        String failAccount = resolveBatchAccountLabel(template, explicitBoundAccount, message);
                        template.triggerFailureSafetyNet(message, maskedTo, t);
                        MailSendException wrapped = (t instanceof MailSendException mse)
                                ? mse
                                : new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送失败: " + t.getMessage(), t);
                        completeBatchItem(results, item.index(),
                                MailSendResultItem.failure(item.index(), message, wrapped.getMessage(), wrapped),
                                template, failAccount, template.classifyFailureOutcome(t), entryStartNanos);
                    } finally {
                        itemStates.set(item.index(), STATE_FINISHED);
                        closeQuietly(message);
                    }
                }
            }

            // 2. 分片内同最终路由账号分组复用连接级物理 Transport 连续投递
            for (Map.Entry<TransportGroupKey, List<PreparedBatchEntry>> group : physicalGroups.entrySet()) {
                if (batchTimedOut.get() || Thread.currentThread().isInterrupted()) {
                    closeGroupMessages(group.getValue(), itemStates);
                    continue;
                }
                deliverAccountGroupWithReusedTransport(
                        template, group.getKey().account(), group.getValue(), batchTimedOut, itemStates, results);
            }
        } finally {
            for (List<PreparedBatchEntry> groupEntries : physicalGroups.values()) {
                closeGroupMessages(groupEntries, itemStates);
            }
        }
    }

    private static void executeSliceWithoutTransportReuse(MailTemplate template,
                                                          MailAccount explicitBoundAccount,
                                                          List<BatchItem> slice,
                                                          AtomicBoolean batchTimedOut,
                                                          AtomicIntegerArray itemStates,
                                                          AtomicReferenceArray<MailSendResultItem> results) {
        for (BatchItem item : slice) {
            if (batchTimedOut.get() || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (!itemStates.compareAndSet(item.index(), STATE_PENDING, STATE_CLAIMED)) {
                continue;
            }
            MailMessage message = item.message();
            long entryStartNanos = System.nanoTime();
            AtomicReference<MailTemplate.BatchDeliveryMetric> metricRef = new AtomicReference<>();
            try {
                MailSendResult result = template.sendForBatch(explicitBoundAccount, message, metricRef);
                MailTemplate.BatchDeliveryMetric metric = metricRef.get();
                String accountLabel = metric != null ? metric.accountName()
                        : resolveBatchAccountLabel(template, explicitBoundAccount, message);
                MailSendOutcome outcome = metric != null ? metric.outcome() : MailSendOutcome.SUCCESS;
                completeBatchItem(results, item.index(), MailSendResultItem.success(item.index(), message, result),
                        template, accountLabel, outcome, entryStartNanos);
            } catch (Throwable t) {
                MailTemplate.BatchDeliveryMetric metric = metricRef.get();
                String accountLabel = metric != null ? metric.accountName()
                        : resolveBatchAccountLabel(template, explicitBoundAccount, message);
                MailSendOutcome outcome = metric != null ? metric.outcome() : template.classifyFailureOutcome(t);
                completeBatchItem(results, item.index(), MailSendResultItem.failure(item.index(), message, t.getMessage(), t),
                        template, accountLabel, outcome, entryStartNanos);
            } finally {
                itemStates.set(item.index(), STATE_FINISHED);
                closeQuietly(message);
            }
        }
    }

    private static void deliverAccountGroupWithReusedTransport(MailTemplate template,
                                                               MailAccount account,
                                                               List<PreparedBatchEntry> entries,
                                                               AtomicBoolean batchTimedOut,
                                                               AtomicIntegerArray itemStates,
                                                               AtomicReferenceArray<MailSendResultItem> results) {
        JavaMailSenderImpl sender = account.getSender();
        boolean sessionTransportSupported = canUseSessionTransport(sender);
        TransportHolder transportHolder = new TransportHolder();

        try {
            for (PreparedBatchEntry entry : entries) {
                MailTemplate.PreparedDelivery prepared = entry.prepared();
                MailMessage message = prepared.message();
                if (batchTimedOut.get() || Thread.currentThread().isInterrupted()) {
                    if (itemStates.compareAndSet(entry.index(), STATE_CLAIMED, STATE_FINISHED)) {
                        closeQuietly(message);
                    }
                    continue;
                }

                try {
                    MailSendResult result = template.executeWithOutboundGuards(account, message, () -> {
                        template.publishEvent(new MailSendingEvent(template, message));

                        MailSendResult sendResult;
                        if (!sessionTransportSupported) {
                            sendResult = template.executeWithCascadeFailover(
                                    account, message, prepared.maskedTo(), prepared.traceId(), prepared.messageId());
                        } else {
                            sendResult = sendSingleEntryWithTransportReuse(
                                    template, account, sender, prepared, transportHolder);
                        }

                        template.publishEvent(new MailSentEvent(template, message, sendResult));
                        return sendResult;
                    });
                    completeBatchItem(results, entry.index(), MailSendResultItem.success(entry.index(), message, result),
                            template, account.getName(), MailSendOutcome.SUCCESS, entry.startNanos());
                } catch (Throwable t) {
                    template.triggerFailureSafetyNet(message, prepared.maskedTo(), t);
                    MailSendException wrapped = (t instanceof MailSendException mse)
                            ? mse
                            : new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件发送失败: " + t.getMessage(), t);
                    completeBatchItem(results, entry.index(),
                            MailSendResultItem.failure(entry.index(), message, wrapped.getMessage(), wrapped),
                            template, account.getName(), template.classifyFailureOutcome(t), entry.startNanos());
                } finally {
                    itemStates.set(entry.index(), STATE_FINISHED);
                    closeQuietly(message);
                }
            }
        } finally {
            closeTransportQuietly(transportHolder.transport);
        }
    }

    private static MailSendResult sendSingleEntryWithTransportReuse(MailTemplate template,
                                                                    MailAccount account,
                                                                    JavaMailSenderImpl sender,
                                                                    MailTemplate.PreparedDelivery prepared,
                                                                    TransportHolder holder) {
        MailMessage message = prepared.message();
        boolean repeatable = template.isRepeatableMessage(message);
        AtomicReference<MailSendException> sizeLimitException = new AtomicReference<>();

        try {
            if (holder.transport != null && !holder.transport.isConnected()) {
                closeTransportQuietly(holder.transport);
                holder.transport = null;
            }
            if (holder.transport == null) {
                holder.transport = openAndConnectTransport(template, account, sender);
            }
            return transmitViaTransport(template, account, message, prepared.maskedTo(),
                    prepared.messageId(), holder.transport, sizeLimitException);
        } catch (Throwable firstEx) {
            MailSendException mappedFirst = mapTransportException(template, firstEx, sizeLimitException.get());
            boolean isAuthFailure = mappedFirst.getResultCode() == MailResultCode.MAIL_AUTHENTICATION_ERROR
                    || MailAccountRegistry.isAuthenticationFailure(firstEx);
            boolean isDeterministic = isAuthFailure
                    || sizeLimitException.get() != null
                    || !template.isRetryableException(mappedFirst);

            if (isDeterministic || !repeatable) {
                if (isAuthFailure) {
                    closeTransportQuietly(holder.transport);
                    holder.transport = null;
                }
                throw mappedFirst;
            }

            // 非认证物理连接断开：安全关闭旧连接并重建重连一次继续投递
            template.getMetricsRecorder().recordRetry(account.getName());
            log.warn("[MailBatch] 复用 Transport 发送异常，尝试安全关闭并重连一次: account=[{}], messageId=[{}], reason={}",
                    account.getName(), prepared.messageId(), mappedFirst.getMessage());
            closeTransportQuietly(holder.transport);
            holder.transport = null;

            AtomicReference<MailSendException> reconnectSizeEx = new AtomicReference<>();
            try {
                holder.transport = openAndConnectTransport(template, account, sender);
                return transmitViaTransport(template, account, message, prepared.maskedTo(),
                        prepared.messageId(), holder.transport, reconnectSizeEx);
            } catch (Throwable reconnectEx) {
                closeTransportQuietly(holder.transport);
                holder.transport = null;
                MailSendException mappedReconnect = mapTransportException(template, reconnectEx, reconnectSizeEx.get());
                if (template.shouldTriggerFailover(account, mappedReconnect, repeatable)) {
                    // 重连仍失败时降级到单封 Failover 备用通道链
                    return template.executeCascadeFailoverOnlyBackups(
                            account, message, prepared.maskedTo(), prepared.traceId(), mappedReconnect);
                }
                throw mappedReconnect;
            }
        }
    }

    private static MailSendResult transmitViaTransport(MailTemplate template,
                                                         MailAccount account,
                                                         MailMessage message,
                                                         String maskedTo,
                                                         String messageId,
                                                         Transport transport,
                                                         AtomicReference<MailSendException> sizeLimitException) throws Exception {
        TrackableMimeMessage mimeMessage = template.buildPreparedMimeMessage(account, message, messageId, null, sizeLimitException);
        mimeMessage.saveChanges();
        Address[] recipients = mimeMessage.getAllRecipients();
        if (recipients == null || recipients.length == 0) {
            throw new MailSendException(MailResultCode.MAIL_RECIPIENT_EMPTY, "收件人列表不能为空");
        }
        long start = System.nanoTime();
        transport.sendMessage(mimeMessage, recipients);
        if (sizeLimitException.get() != null) {
            throw sizeLimitException.get();
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        log.info("Email sent via reused Transport to [{}] in {} ms, account: [{}], messageId: [{}]",
                maskedTo, duration.toMillis(), account.getName(), messageId);
        return MailSendResult.success(messageId, new ArrayList<>(message.getTo()), duration);
    }

    private static Transport openAndConnectTransport(MailTemplate template,
                                                    MailAccount account,
                                                    JavaMailSenderImpl sender) throws Exception {
        String protocol = (sender.getProtocol() != null && !sender.getProtocol().isBlank())
                ? sender.getProtocol()
                : (account.getProtocol() != null && !account.getProtocol().isBlank() ? account.getProtocol() : "smtp");
        String host = (sender.getHost() != null && !sender.getHost().isBlank()) ? sender.getHost() : account.getHost();
        int port = sender.getPort();

        AtomicReference<Transport> transportRef = new AtomicReference<>();
        try {
            template.getAccountRegistry().executeWithOAuth2Refresh(account, sender, template.getAccessTokenProvider(), () -> {
                closeTransportQuietly(transportRef.getAndSet(null));
                Transport candidate = sender.getSession().getTransport(protocol);
                transportRef.set(candidate);
                candidate.connect(host, port, sender.getUsername(), sender.getPassword());
            });
            return transportRef.get();
        } catch (Throwable t) {
            closeTransportQuietly(transportRef.getAndSet(null));
            throw t;
        }
    }

    private static MailSendException mapTransportException(MailTemplate template,
                                                           Throwable throwable,
                                                           MailSendException sizeLimitEx) {
        if (sizeLimitEx != null) {
            return sizeLimitEx;
        }
        if (throwable instanceof MailSendException mse) {
            return mse;
        }
        if (throwable instanceof Exception ex) {
            return template.mapPhysicalException(ex);
        }
        return new MailSendException(MailResultCode.MAIL_SEND_ERROR, "邮件批量发送异常: " + throwable.getMessage(), throwable);
    }

    private static boolean canUseSessionTransport(JavaMailSenderImpl sender) {
        if (sender == null || sender.getSession() == null) {
            return false;
        }
        if (!JavaMailSenderImpl.class.equals(sender.getClass())) {
            try {
                sender.getClass().getDeclaredMethod("send", MimeMessage.class);
                return false;
            } catch (NoSuchMethodException ignored) {
                return true;
            }
        }
        return true;
    }

    private static void closeGroupMessages(List<PreparedBatchEntry> entries, AtomicIntegerArray itemStates) {
        for (PreparedBatchEntry entry : entries) {
            if (itemStates.compareAndSet(entry.index(), STATE_CLAIMED, STATE_FINISHED)) {
                closeQuietly(entry.prepared().message());
            }
        }
    }

    private static void closeTransportQuietly(Transport transport) {
        if (transport != null) {
            try {
                transport.close();
            } catch (Throwable ignored) {
                // 静默忽略关闭异常，保证释放路径绝不掩盖主业务异常
            }
        }
    }

    private static void completeBatchItem(AtomicReferenceArray<MailSendResultItem> results,
                                          int index,
                                          MailSendResultItem itemResult,
                                          MailTemplate template,
                                          String accountName,
                                          MailSendOutcome outcome,
                                          long startNanos) {
        if (results.compareAndSet(index, null, itemResult)) {
            template.getMetricsRecorder().recordSend(accountName, outcome,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos)));
        }
    }

    private static String resolveBatchAccountLabel(MailTemplate template,
                                                   MailAccount explicitBoundAccount,
                                                   MailMessage message) {
        if (explicitBoundAccount != null) {
            return explicitBoundAccount.getName();
        }
        return template.resolvePreFailureMetricAccount(message);
    }

    private static void closeQuietly(MailMessage message) {
        if (message != null) {
            message.close();
        }
    }

    private record BatchItem(int index, MailMessage message) {
    }

    private record PreparedBatchEntry(int index, MailTemplate.PreparedDelivery prepared, long startNanos) {
    }

    private static final class TransportHolder {
        private Transport transport;
    }

    private static final class TransportGroupKey {
        private final MailAccount account;
        private final JavaMailSenderImpl sender;

        private TransportGroupKey(MailAccount account, JavaMailSenderImpl sender) {
            this.account = account;
            this.sender = sender;
        }

        private MailAccount account() {
            return account;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransportGroupKey that)) {
                return false;
            }
            return this.sender == that.sender && Objects.equals(this.account.getName(), that.account.getName());
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(sender);
            result = 31 * result + (account.getName() != null ? account.getName().hashCode() : 0);
            return result;
        }
    }
}
