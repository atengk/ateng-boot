package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
import io.github.atengk.boot.flyway.model.BatchOperationResult;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import io.github.atengk.boot.flyway.model.MigrationSummary;
import io.github.atengk.boot.flyway.model.TargetFailure;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 批量与有界并发 Flyway 迁移协调执行器
 * <p>
 * 负责串行批处理、依托 Core 受管线程池与 {@link AsyncContext} 的有界并发流水线、
 * 超时/中断确定性取消全部工作线程、以及批量回执与全局摘要的无锁合并。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
final class FlywayBatchExecutor {

    private FlywayBatchExecutor() {
    }

    @FunctionalInterface
    interface TargetItemRunner {
        MigrationOutcome run(String targetKey, FlywayExecutionMode mode);
    }

    record AsyncBatchHandle(
            CompletableFuture<BatchMigrationResult> future,
            AtomicBoolean aborted,
            CompletableFuture<?>[] workers,
            Set<Thread> activeThreads) {

        void cancelAll() {
            cancelAll(Duration.ZERO);
        }

        void cancelAll(Duration awaitTimeout) {
            cancelWorkers(aborted, activeThreads, workers, future, awaitTimeout);
        }
    }

    static <T> BatchOperationResult<T> executeGenericBatch(List<String> targetKeys,
                                                           BatchExecutionPolicy policy,
                                                           int concurrency,
                                                           Duration batchTimeout,
                                                           Executor executor,
                                                           FlywayResultCode fallbackErrorCode,
                                                           Function<String, T> action,
                                                           Function<String, String> sanitizer) {
        if (CollectionUtils.isEmpty(targetKeys)) {
            return emptyGenericResult(policy);
        }
        SlotBatchSnapshot<T> snapshot;
        if (concurrency <= 1 || targetKeys.size() <= 1) {
            snapshot = runSlotsSerially(targetKeys, policy, action, fallbackErrorCode, sanitizer, null);
        } else {
            snapshot = awaitSlotsConcurrently(targetKeys, policy, concurrency, batchTimeout, executor,
                    action, fallbackErrorCode, sanitizer, null);
        }
        return assembleGenericResult(policy, snapshot);
    }

    static <T> BatchOperationResult<T> emptyGenericResult(BatchExecutionPolicy policy) {
        return BatchOperationResult.<T>builder()
                .policy(policy)
                .executedAt(Instant.now())
                .totalDuration(Duration.ZERO)
                .totalTargets(0)
                .successCount(0)
                .failedCount(0)
                .results(Collections.emptyMap())
                .failures(Collections.emptyList())
                .targetDurations(Collections.emptyMap())
                .build();
    }

    static BatchMigrationResult executeSerially(List<String> targetKeys,
                                                FlywayExecutionMode mode,
                                                BatchExecutionPolicy policy,
                                                TargetItemRunner itemRunner,
                                                Function<String, String> sanitizer,
                                                Consumer<MigrationSummary> summaryRecorder,
                                                boolean recordSummary) {
        Consumer<SlotBatchSnapshot<MigrationOutcome>> failFastRecorder = (recordSummary && summaryRecorder != null)
                ? partial -> summaryRecorder.accept(toSummary(assembleMigrationResult(mode, policy, partial)))
                : null;
        SlotBatchSnapshot<MigrationOutcome> snapshot = runSlotsSerially(
                targetKeys,
                policy,
                key -> itemRunner.run(key, mode),
                resolveErrorCode(mode),
                sanitizer,
                failFastRecorder);
        BatchMigrationResult result = assembleMigrationResult(mode, policy, snapshot);
        if (recordSummary && summaryRecorder != null) {
            summaryRecorder.accept(toSummary(result));
        }
        return result;
    }

    static BatchMigrationResult executeConcurrently(List<String> targetKeys,
                                                    FlywayExecutionMode mode,
                                                    BatchExecutionPolicy policy,
                                                    int concurrency,
                                                    Duration batchTimeout,
                                                    Executor executor,
                                                    TargetItemRunner itemRunner,
                                                    Function<String, String> sanitizer,
                                                    Consumer<MigrationSummary> summaryRecorder,
                                                    boolean recordSummary) {
        Consumer<SlotBatchSnapshot<MigrationOutcome>> failFastRecorder = (recordSummary && summaryRecorder != null)
                ? partial -> summaryRecorder.accept(toSummary(assembleMigrationResult(mode, policy, partial)))
                : null;
        SlotBatchSnapshot<MigrationOutcome> snapshot = awaitSlotsConcurrently(
                targetKeys,
                policy,
                concurrency,
                batchTimeout,
                executor,
                key -> itemRunner.run(key, mode),
                resolveErrorCode(mode),
                sanitizer,
                failFastRecorder);
        BatchMigrationResult result = assembleMigrationResult(mode, policy, snapshot);
        if (recordSummary && summaryRecorder != null) {
            summaryRecorder.accept(toSummary(result));
        }
        return result;
    }

    static AsyncBatchHandle launchAsync(List<String> targetKeys,
                                        FlywayExecutionMode mode,
                                        BatchExecutionPolicy policy,
                                        int concurrency,
                                        Executor executor,
                                        TargetItemRunner itemRunner,
                                        Function<String, String> sanitizer) {
        SlotAsyncHandle<MigrationOutcome> rawHandle = launchSlotsAsync(
                targetKeys,
                policy,
                concurrency,
                executor,
                key -> itemRunner.run(key, mode),
                resolveErrorCode(mode),
                sanitizer);
        CompletableFuture<BatchMigrationResult> combined = rawHandle.future().handle((snapshot, ex) -> {
            if (ex != null) {
                Throwable unwrapped = ex.getCause() != null ? ex.getCause() : ex;
                if (unwrapped instanceof SlotFailFastException failFast) {
                    @SuppressWarnings("unchecked")
                    SlotBatchSnapshot<MigrationOutcome> partial =
                            (SlotBatchSnapshot<MigrationOutcome>) failFast.getPartialSnapshot();
                    BatchMigrationResult partialResult = assembleMigrationResult(mode, policy, partial);
                    throw new BatchFailFastException(failFast.getCauseException(), partialResult);
                }
                if (unwrapped instanceof RuntimeException runtimeEx) {
                    throw runtimeEx;
                }
                throw new FlywayException(resolveErrorCode(mode),
                        "并发批量迁移执行失败: " + sanitizer.apply(unwrapped.getMessage()), unwrapped);
            }
            return assembleMigrationResult(mode, policy, snapshot);
        });
        return new AsyncBatchHandle(combined, rawHandle.aborted(), rawHandle.workers(), rawHandle.activeThreads());
    }

    static BatchMigrationResult emptyResult(FlywayExecutionMode mode, BatchExecutionPolicy policy) {
        Instant now = Instant.now();
        return BatchMigrationResult.builder()
                .mode(mode)
                .policy(policy)
                .executedAt(now)
                .totalDuration(Duration.ZERO)
                .totalTargets(0)
                .successCount(0)
                .skippedCount(0)
                .failedCount(0)
                .outcomes(Collections.emptyList())
                .failures(Collections.emptyList())
                .build();
    }

    static BatchMigrationResult mergeBatchResults(FlywayExecutionMode mode,
                                                    BatchExecutionPolicy policy,
                                                    BatchMigrationResult first,
                                                    BatchMigrationResult second) {
        List<MigrationOutcome> outcomes = new ArrayList<>();
        List<TargetFailure> failures = new ArrayList<>();
        if (first != null && !CollectionUtils.isEmpty(first.getOutcomes())) {
            outcomes.addAll(first.getOutcomes());
        }
        if (first != null && !CollectionUtils.isEmpty(first.getFailures())) {
            failures.addAll(first.getFailures());
        }
        if (second != null && !CollectionUtils.isEmpty(second.getOutcomes())) {
            outcomes.addAll(second.getOutcomes());
        }
        if (second != null && !CollectionUtils.isEmpty(second.getFailures())) {
            failures.addAll(second.getFailures());
        }
        int success = (first != null ? first.getSuccessCount() : 0)
                + (second != null ? second.getSuccessCount() : 0);
        int skipped = (first != null ? first.getSkippedCount() : 0)
                + (second != null ? second.getSkippedCount() : 0);
        int failed = (first != null ? first.getFailedCount() : 0)
                + (second != null ? second.getFailedCount() : 0);
        Duration totalDuration = Duration.ZERO;
        if (first != null && first.getTotalDuration() != null) {
            totalDuration = totalDuration.plus(first.getTotalDuration());
        }
        if (second != null && second.getTotalDuration() != null) {
            totalDuration = totalDuration.plus(second.getTotalDuration());
        }
        return BatchMigrationResult.builder()
                .mode(mode)
                .policy(policy)
                .executedAt(Instant.now())
                .totalDuration(totalDuration)
                .totalTargets(outcomes.size())
                .successCount(success)
                .skippedCount(skipped)
                .failedCount(failed)
                .outcomes(Collections.unmodifiableList(outcomes))
                .failures(Collections.unmodifiableList(failures))
                .build();
    }

    static MigrationSummary toSummary(BatchMigrationResult batch) {
        return MigrationSummary.builder()
                .mode(batch.getMode())
                .executedAt(batch.getExecutedAt())
                .totalDuration(batch.getTotalDuration())
                .totalTargets(batch.getTotalTargets())
                .successCount(batch.getSuccessCount())
                .failedCount(batch.getFailedCount())
                .skippedCount(batch.getSkippedCount())
                .outcomes(batch.getOutcomes())
                .build();
    }

    static MigrationSummary upsertSummaryOutcome(MigrationSummary existing,
                                                 FlywayExecutionMode mode,
                                                 MigrationOutcome outcome) {
        Map<String, MigrationOutcome> ordered = new LinkedHashMap<>();
        if (existing != null && !CollectionUtils.isEmpty(existing.getOutcomes())) {
            for (MigrationOutcome item : existing.getOutcomes()) {
                if (item != null && item.getTargetKey() != null) {
                    ordered.put(item.getTargetKey(), item);
                }
            }
        }
        ordered.put(outcome.getTargetKey(), outcome);
        FlywayExecutionMode effectiveMode = existing != null && existing.getMode() != null
                && !(existing.getMode() == FlywayExecutionMode.DISABLED && !outcome.isSkipped())
                ? existing.getMode()
                : mode;
        Instant executedAt = outcome.getExecutedAt() != null ? outcome.getExecutedAt() : Instant.now();
        return rebuildSummary(effectiveMode, executedAt, ordered);
    }

    static MigrationSummary removeSummaryOutcome(MigrationSummary existing, String targetKey) {
        if (existing == null || CollectionUtils.isEmpty(existing.getOutcomes()) || targetKey == null) {
            return existing;
        }
        Map<String, MigrationOutcome> ordered = new LinkedHashMap<>();
        boolean removed = false;
        for (MigrationOutcome item : existing.getOutcomes()) {
            if (item == null || item.getTargetKey() == null) {
                continue;
            }
            if (Objects.equals(item.getTargetKey(), targetKey)) {
                removed = true;
                continue;
            }
            ordered.put(item.getTargetKey(), item);
        }
        if (!removed) {
            return existing;
        }
        return rebuildSummary(existing.getMode(), Instant.now(), ordered);
    }

    private static MigrationSummary rebuildSummary(FlywayExecutionMode mode,
                                                   Instant executedAt,
                                                   Map<String, MigrationOutcome> ordered) {
        List<MigrationOutcome> outcomes = List.copyOf(ordered.values());
        int success = 0;
        int failed = 0;
        int skipped = 0;
        Duration total = Duration.ZERO;
        for (MigrationOutcome item : outcomes) {
            if (item.isSkipped()) {
                skipped++;
            } else if (item.isSuccess()) {
                success++;
            } else {
                failed++;
            }
            if (item.getDuration() != null) {
                total = total.plus(item.getDuration());
            }
        }
        return MigrationSummary.builder()
                .mode(mode)
                .executedAt(executedAt)
                .totalDuration(total)
                .totalTargets(outcomes.size())
                .successCount(success)
                .failedCount(failed)
                .skippedCount(skipped)
                .outcomes(outcomes)
                .build();
    }

    private static <T> SlotBatchSnapshot<T> runSlotsSerially(
            List<String> targetKeys,
            BatchExecutionPolicy policy,
            Function<String, T> action,
            FlywayResultCode fallbackCode,
            Function<String, String> sanitizer,
            Consumer<SlotBatchSnapshot<T>> failFastRecorder) {
        Instant batchStart = Instant.now();
        List<SlotResult<T>> collected = new ArrayList<>(targetKeys.size());
        for (String key : targetKeys) {
            SlotResult<T> slot = runSlotSafely(key, action, fallbackCode, sanitizer);
            collected.add(slot);
            if (slot.failure() != null && policy == BatchExecutionPolicy.FAIL_FAST) {
                if (failFastRecorder != null) {
                    failFastRecorder.accept(new SlotBatchSnapshot<>(batchStart, Instant.now(), collected));
                }
                throw slot.fastFailure();
            }
        }
        return new SlotBatchSnapshot<>(batchStart, Instant.now(), collected);
    }

    private static <T> SlotBatchSnapshot<T> awaitSlotsConcurrently(
            List<String> targetKeys,
            BatchExecutionPolicy policy,
            int concurrency,
            Duration batchTimeout,
            Executor executor,
            Function<String, T> action,
            FlywayResultCode fallbackCode,
            Function<String, String> sanitizer,
            Consumer<SlotBatchSnapshot<T>> failFastRecorder) {
        SlotAsyncHandle<T> handle = launchSlotsAsync(
                targetKeys, policy, concurrency, executor, action, fallbackCode, sanitizer);
        try {
            return handle.future().get(batchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SlotFailFastException failFast) {
                if (failFastRecorder != null) {
                    @SuppressWarnings("unchecked")
                    SlotBatchSnapshot<T> partial = (SlotBatchSnapshot<T>) failFast.getPartialSnapshot();
                    failFastRecorder.accept(partial);
                }
                throw failFast.getCauseException();
            }
            if (cause instanceof FlywayException fe) {
                throw fe;
            }
            throw new FlywayException(fallbackCode,
                    "并发批量操作执行失败: " + sanitizer.apply(cause != null ? cause.getMessage() : e.getMessage()), cause);
        } catch (TimeoutException e) {
            handle.cancelAll();
            throw new FlywayException(fallbackCode,
                    "并发批量操作等待超时 [timeout=" + batchTimeout + "]", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancelAll();
            throw new FlywayException(fallbackCode, "并发批量操作被中断", e);
        }
    }

    private static <T> SlotAsyncHandle<T> launchSlotsAsync(
            List<String> targetKeys,
            BatchExecutionPolicy policy,
            int concurrency,
            Executor executor,
            Function<String, T> action,
            FlywayResultCode fallbackCode,
            Function<String, String> sanitizer) {
        Instant batchStart = Instant.now();
        int total = targetKeys.size();
        int workerCount = Math.max(1, Math.min(concurrency, total));

        @SuppressWarnings("unchecked")
        SlotResult<T>[] slots = (SlotResult<T>[]) new SlotResult<?>[total];
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicReference<RuntimeException> firstFastFailure = new AtomicReference<>();

        CompletableFuture<?>[] workers = new CompletableFuture<?>[workerCount];
        Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
        for (int w = 0; w < workerCount; w++) {
            Runnable workerTask = AsyncContext.wrap(() -> {
                Thread currentThread = Thread.currentThread();
                activeThreads.add(currentThread);
                try {
                    while (!aborted.get() && !currentThread.isInterrupted()) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= total) {
                            break;
                        }
                        SlotResult<T> slot = runSlotSafely(targetKeys.get(idx), action, fallbackCode, sanitizer);
                        slots[idx] = slot;
                        if (slot.failure() != null && policy == BatchExecutionPolicy.FAIL_FAST) {
                            aborted.set(true);
                            firstFastFailure.compareAndSet(null, slot.fastFailure());
                            break;
                        }
                    }
                } finally {
                    activeThreads.remove(currentThread);
                }
            });
            workers[w] = CompletableFuture.runAsync(workerTask, executor);
        }

        CompletableFuture<SlotBatchSnapshot<T>> combined = CompletableFuture.allOf(workers).thenApply(ignored -> {
            List<SlotResult<T>> collected = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                if (slots[i] != null) {
                    collected.add(slots[i]);
                }
            }
            SlotBatchSnapshot<T> snapshot = new SlotBatchSnapshot<>(batchStart, Instant.now(), collected);
            RuntimeException fastError = firstFastFailure.get();
            if (fastError != null) {
                throw new SlotFailFastException(fastError, snapshot);
            }
            return snapshot;
        });
        return new SlotAsyncHandle<>(combined, aborted, workers, activeThreads);
    }

    private static <T> SlotResult<T> runSlotSafely(String key,
                                                   Function<String, T> action,
                                                   FlywayResultCode fallbackCode,
                                                   Function<String, String> sanitizer) {
        Instant itemStart = Instant.now();
        try {
            T value = action.apply(key);
            Duration elapsed = Duration.between(itemStart, Instant.now());
            return new SlotResult<>(key, value, null, elapsed, null);
        } catch (FlywayException e) {
            Duration elapsed = Duration.between(itemStart, Instant.now());
            String safeMessage = sanitizer.apply(e.getMessage());
            TargetFailure failure = TargetFailure.builder()
                    .targetKey(key)
                    .errorCode(e.getCode())
                    .errorMessage(safeMessage)
                    .duration(elapsed)
                    .build();
            return new SlotResult<>(key, null, failure, elapsed, e);
        } catch (Exception e) {
            Duration elapsed = Duration.between(itemStart, Instant.now());
            String safeMessage = sanitizer.apply(e.getMessage());
            TargetFailure failure = TargetFailure.builder()
                    .targetKey(key)
                    .errorCode(fallbackCode.getCode())
                    .errorMessage(safeMessage)
                    .duration(elapsed)
                    .build();
            FlywayException translated = new FlywayException(fallbackCode,
                    "目标数据源 [" + key + "] 批量执行失败: " + safeMessage, e);
            return new SlotResult<>(key, null, failure, elapsed, translated);
        }
    }

    private static BatchMigrationResult assembleMigrationResult(FlywayExecutionMode mode,
                                                                BatchExecutionPolicy policy,
                                                                SlotBatchSnapshot<MigrationOutcome> snapshot) {
        List<MigrationOutcome> outcomes = new ArrayList<>(snapshot.slots().size());
        List<TargetFailure> failures = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;
        for (SlotResult<MigrationOutcome> slot : snapshot.slots()) {
            if (slot.failure() == null) {
                MigrationOutcome outcome = slot.value();
                outcomes.add(outcome);
                if (outcome.isSkipped()) {
                    skippedCount++;
                } else {
                    successCount++;
                }
            } else {
                failures.add(slot.failure());
                outcomes.add(buildFailureOutcome(slot.targetKey(), mode, snapshot.finishedAt(), slot.duration(),
                        slot.failure().getErrorMessage()));
            }
        }
        return BatchMigrationResult.builder()
                .mode(mode)
                .policy(policy)
                .executedAt(snapshot.finishedAt())
                .totalDuration(Duration.between(snapshot.startedAt(), snapshot.finishedAt()))
                .totalTargets(outcomes.size())
                .successCount(successCount)
                .skippedCount(skippedCount)
                .failedCount(failures.size())
                .outcomes(Collections.unmodifiableList(outcomes))
                .failures(Collections.unmodifiableList(failures))
                .build();
    }

    private static <T> BatchOperationResult<T> assembleGenericResult(BatchExecutionPolicy policy,
                                                                     SlotBatchSnapshot<T> snapshot) {
        Map<String, T> results = new LinkedHashMap<>();
        List<TargetFailure> failures = new ArrayList<>();
        Map<String, Duration> durations = new LinkedHashMap<>();
        int successCount = 0;
        for (SlotResult<T> slot : snapshot.slots()) {
            durations.put(slot.targetKey(), slot.duration() != null ? slot.duration() : Duration.ZERO);
            if (slot.failure() == null) {
                results.put(slot.targetKey(), slot.value());
                successCount++;
            } else {
                failures.add(slot.failure());
            }
        }
        return BatchOperationResult.<T>builder()
                .policy(policy)
                .executedAt(snapshot.finishedAt())
                .totalDuration(Duration.between(snapshot.startedAt(), snapshot.finishedAt()))
                .totalTargets(snapshot.slots().size())
                .successCount(successCount)
                .failedCount(failures.size())
                .results(results)
                .failures(failures)
                .targetDurations(durations)
                .build();
    }

    private static MigrationOutcome buildFailureOutcome(String targetKey,
                                                        FlywayExecutionMode mode,
                                                        Instant finishedAt,
                                                        Duration duration,
                                                        String errorMessage) {
        return MigrationOutcome.builder()
                .targetKey(targetKey)
                .mode(mode)
                .success(false)
                .skipped(false)
                .migrationsExecuted(0)
                .duration(duration)
                .executedAt(finishedAt)
                .errorMessage(errorMessage)
                .warnings(Collections.emptyList())
                .build();
    }

    private static FlywayResultCode resolveErrorCode(FlywayExecutionMode mode) {
        return mode == FlywayExecutionMode.VALIDATE_ONLY
                ? FlywayResultCode.VALIDATION_FAILED
                : FlywayResultCode.MIGRATION_FAILED;
    }

    private static void cancelWorkers(AtomicBoolean aborted,
                                      Set<Thread> activeThreads,
                                      CompletableFuture<?>[] workers,
                                      CompletableFuture<?> future,
                                      Duration awaitTimeout) {
        aborted.set(true);
        for (Thread activeThread : activeThreads) {
            if (activeThread != null && activeThread != Thread.currentThread()) {
                activeThread.interrupt();
            }
        }
        if (awaitTimeout != null && !awaitTimeout.isZero() && !awaitTimeout.isNegative()
                && !activeThreads.isEmpty() && workers != null) {
            awaitWorkersQuietly(workers, awaitTimeout.toMillis());
        }
        if (workers != null) {
            for (CompletableFuture<?> worker : workers) {
                if (worker != null && !worker.isDone()) {
                    worker.cancel(true);
                }
            }
        }
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void awaitWorkersQuietly(CompletableFuture<?>[] workers, long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (CompletableFuture<?> worker : workers) {
            if (worker == null || worker.isDone()) {
                continue;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                worker.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // 工作线程在中止或中断后完成收尾即可，停机阶段不向上传播异常
            }
        }
    }

    private record SlotResult<T>(
            String targetKey,
            T value,
            TargetFailure failure,
            Duration duration,
            RuntimeException fastFailure) {
    }

    private record SlotBatchSnapshot<T>(
            Instant startedAt,
            Instant finishedAt,
            List<SlotResult<T>> slots) {
    }

    private record SlotAsyncHandle<T>(
            CompletableFuture<SlotBatchSnapshot<T>> future,
            AtomicBoolean aborted,
            CompletableFuture<?>[] workers,
            Set<Thread> activeThreads) {

        void cancelAll() {
            cancelWorkers(aborted, activeThreads, workers, future, Duration.ZERO);
        }
    }

    private static final class SlotFailFastException extends RuntimeException {
        private final RuntimeException causeException;
        private final SlotBatchSnapshot<?> partialSnapshot;

        SlotFailFastException(RuntimeException causeException, SlotBatchSnapshot<?> partialSnapshot) {
            super(causeException.getMessage(), causeException);
            this.causeException = causeException;
            this.partialSnapshot = partialSnapshot;
        }

        RuntimeException getCauseException() {
            return causeException;
        }

        SlotBatchSnapshot<?> getPartialSnapshot() {
            return partialSnapshot;
        }
    }

    static final class BatchFailFastException extends RuntimeException {
        private final RuntimeException causeException;
        private final BatchMigrationResult partialResult;

        BatchFailFastException(RuntimeException causeException, BatchMigrationResult partialResult) {
            super(causeException.getMessage(), causeException);
            this.causeException = causeException;
            this.partialResult = partialResult;
        }

        RuntimeException getCauseException() {
            return causeException;
        }

        BatchMigrationResult getPartialResult() {
            return partialResult;
        }
    }
}
