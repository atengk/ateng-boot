package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.model.BatchMigrationResult;
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
import java.util.concurrent.CompletableFuture;
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
            CompletableFuture<?>[] workers) {

        void cancelAll() {
            aborted.set(true);
            for (CompletableFuture<?> worker : workers) {
                if (worker != null && !worker.isDone()) {
                    worker.cancel(true);
                }
            }
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    static BatchMigrationResult executeSerially(List<String> targetKeys,
                                                FlywayExecutionMode mode,
                                                BatchExecutionPolicy policy,
                                                TargetItemRunner itemRunner,
                                                Function<String, String> sanitizer,
                                                Consumer<MigrationSummary> summaryRecorder,
                                                boolean recordSummary) {
        Instant batchStart = Instant.now();
        List<MigrationOutcome> outcomes = new ArrayList<>(targetKeys.size());
        List<TargetFailure> failures = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;

        for (String key : targetKeys) {
            ItemResult item = runItemSafely(key, mode, itemRunner, sanitizer);
            outcomes.add(item.outcome());
            if (item.failure() == null) {
                if (item.outcome().isSkipped()) {
                    skippedCount++;
                } else {
                    successCount++;
                }
            } else {
                failures.add(item.failure());
                if (policy == BatchExecutionPolicy.FAIL_FAST) {
                    if (recordSummary && summaryRecorder != null) {
                        summaryRecorder.accept(toSummary(assembleResult(mode, policy, batchStart,
                                outcomes, failures, successCount, skippedCount)));
                    }
                    throw item.fastFailure();
                }
            }
        }

        BatchMigrationResult result = assembleResult(mode, policy, batchStart,
                outcomes, failures, successCount, skippedCount);
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
        AsyncBatchHandle handle = launchAsync(
                targetKeys, mode, policy, concurrency, executor, itemRunner, sanitizer);
        try {
            BatchMigrationResult result = handle.future().get(batchTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (recordSummary && summaryRecorder != null) {
                summaryRecorder.accept(toSummary(result));
            }
            return result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BatchFailFastException failFast) {
                if (recordSummary && summaryRecorder != null) {
                    summaryRecorder.accept(toSummary(failFast.getPartialResult()));
                }
                throw failFast.getCauseException();
            }
            if (cause instanceof FlywayException fe) {
                throw fe;
            }
            throw new FlywayException(resolveErrorCode(mode),
                    "并发批量迁移执行失败: " + sanitizer.apply(cause != null ? cause.getMessage() : e.getMessage()), cause);
        } catch (TimeoutException e) {
            handle.cancelAll();
            throw new FlywayException(resolveErrorCode(mode),
                    "并发批量迁移等待超时 [timeout=" + batchTimeout + "]", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancelAll();
            throw new FlywayException(resolveErrorCode(mode), "并发批量迁移被中断", e);
        }
    }

    static AsyncBatchHandle launchAsync(List<String> targetKeys,
                                        FlywayExecutionMode mode,
                                        BatchExecutionPolicy policy,
                                        int concurrency,
                                        Executor executor,
                                        TargetItemRunner itemRunner,
                                        Function<String, String> sanitizer) {
        Instant batchStart = Instant.now();
        int total = targetKeys.size();
        int workerCount = Math.max(1, Math.min(concurrency, total));

        MigrationOutcome[] outcomeSlots = new MigrationOutcome[total];
        TargetFailure[] failureSlots = new TargetFailure[total];
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicReference<RuntimeException> firstFastFailure = new AtomicReference<>();

        CompletableFuture<?>[] workers = new CompletableFuture<?>[workerCount];
        for (int w = 0; w < workerCount; w++) {
            Runnable workerTask = AsyncContext.wrap(() -> {
                while (!aborted.get() && !Thread.currentThread().isInterrupted()) {
                    int idx = cursor.getAndIncrement();
                    if (idx >= total) {
                        break;
                    }
                    ItemResult item = runItemSafely(targetKeys.get(idx), mode, itemRunner, sanitizer);
                    outcomeSlots[idx] = item.outcome();
                    if (item.failure() != null) {
                        failureSlots[idx] = item.failure();
                        if (policy == BatchExecutionPolicy.FAIL_FAST) {
                            aborted.set(true);
                            firstFastFailure.compareAndSet(null, item.fastFailure());
                            break;
                        }
                    }
                }
            });
            workers[w] = CompletableFuture.runAsync(workerTask, executor);
        }

        CompletableFuture<BatchMigrationResult> combined = CompletableFuture.allOf(workers).thenApply(ignored -> {
            List<MigrationOutcome> outcomes = new ArrayList<>(total);
            List<TargetFailure> failures = new ArrayList<>();
            int successCount = 0;
            int skippedCount = 0;
            for (int i = 0; i < total; i++) {
                MigrationOutcome o = outcomeSlots[i];
                if (o == null) {
                    continue;
                }
                outcomes.add(o);
                if (failureSlots[i] != null) {
                    failures.add(failureSlots[i]);
                } else if (o.isSkipped()) {
                    skippedCount++;
                } else {
                    successCount++;
                }
            }
            BatchMigrationResult result = assembleResult(mode, policy, batchStart,
                    outcomes, failures, successCount, skippedCount);
            RuntimeException fastError = firstFastFailure.get();
            if (fastError != null) {
                throw new BatchFailFastException(fastError, result);
            }
            return result;
        });
        return new AsyncBatchHandle(combined, aborted, workers);
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
                .mode(existing != null && existing.getMode() != null ? existing.getMode() : mode)
                .executedAt(outcome.getExecutedAt() != null ? outcome.getExecutedAt() : Instant.now())
                .totalDuration(total)
                .totalTargets(outcomes.size())
                .successCount(success)
                .failedCount(failed)
                .skippedCount(skipped)
                .outcomes(outcomes)
                .build();
    }

    private static ItemResult runItemSafely(String key,
                                            FlywayExecutionMode mode,
                                            TargetItemRunner itemRunner,
                                            Function<String, String> sanitizer) {
        Instant itemStart = Instant.now();
        try {
            MigrationOutcome outcome = itemRunner.run(key, mode);
            return new ItemResult(outcome, null, null);
        } catch (FlywayException e) {
            String safeMessage = sanitizer.apply(e.getMessage());
            MigrationOutcome failureOutcome = buildFailureOutcome(key, mode, itemStart, safeMessage);
            TargetFailure failure = TargetFailure.builder()
                    .targetKey(key)
                    .errorCode(e.getCode())
                    .errorMessage(safeMessage)
                    .duration(failureOutcome.getDuration())
                    .build();
            return new ItemResult(failureOutcome, failure, e);
        } catch (Exception e) {
            String safeMessage = sanitizer.apply(e.getMessage());
            MigrationOutcome failureOutcome = buildFailureOutcome(key, mode, itemStart, safeMessage);
            FlywayResultCode code = resolveErrorCode(mode);
            TargetFailure failure = TargetFailure.builder()
                    .targetKey(key)
                    .errorCode(code.getCode())
                    .errorMessage(safeMessage)
                    .duration(failureOutcome.getDuration())
                    .build();
            FlywayException translated = new FlywayException(code,
                    "目标数据源 [" + key + "] 批量执行失败: " + safeMessage, e);
            return new ItemResult(failureOutcome, failure, translated);
        }
    }

    private static MigrationOutcome buildFailureOutcome(String targetKey,
                                                        FlywayExecutionMode mode,
                                                        Instant start,
                                                        String errorMessage) {
        Instant finishedAt = Instant.now();
        return MigrationOutcome.builder()
                .targetKey(targetKey)
                .mode(mode)
                .success(false)
                .skipped(false)
                .migrationsExecuted(0)
                .duration(Duration.between(start, finishedAt))
                .executedAt(finishedAt)
                .errorMessage(errorMessage)
                .warnings(Collections.emptyList())
                .build();
    }

    private static BatchMigrationResult assembleResult(FlywayExecutionMode mode,
                                                       BatchExecutionPolicy policy,
                                                       Instant batchStart,
                                                       List<MigrationOutcome> outcomes,
                                                       List<TargetFailure> failures,
                                                       int successCount,
                                                       int skippedCount) {
        Instant finishedAt = Instant.now();
        return BatchMigrationResult.builder()
                .mode(mode)
                .policy(policy)
                .executedAt(finishedAt)
                .totalDuration(Duration.between(batchStart, finishedAt))
                .totalTargets(outcomes.size())
                .successCount(successCount)
                .skippedCount(skippedCount)
                .failedCount(failures.size())
                .outcomes(Collections.unmodifiableList(outcomes))
                .failures(Collections.unmodifiableList(failures))
                .build();
    }

    private static FlywayResultCode resolveErrorCode(FlywayExecutionMode mode) {
        return mode == FlywayExecutionMode.VALIDATE_ONLY
                ? FlywayResultCode.VALIDATION_FAILED
                : FlywayResultCode.MIGRATION_FAILED;
    }

    private record ItemResult(MigrationOutcome outcome, TargetFailure failure, RuntimeException fastFailure) {
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
