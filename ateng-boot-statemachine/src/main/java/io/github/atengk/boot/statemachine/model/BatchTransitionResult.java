package io.github.atengk.boot.statemachine.model;

import io.github.atengk.boot.statemachine.exception.StateMachineException;

import java.util.*;

/**
 * 大批量切片状态流转结构化聚合回执
 *
 * @param <S> 业务状态类型
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-11-01
 */
public class BatchTransitionResult<S, T> {

    private final int totalCount;
    private final int successCount;
    private final int failureCount;
    private final List<T> successEntities;
    private final Map<T, StateMachineException> failures;

    public BatchTransitionResult(int totalCount, List<T> successEntities, Map<T, StateMachineException> failures) {
        this.totalCount = totalCount;
        this.successEntities = successEntities != null ? Collections.unmodifiableList(new ArrayList<>(successEntities)) : Collections.emptyList();
        this.failures = failures != null ? Collections.unmodifiableMap(new LinkedHashMap<>(failures)) : Collections.emptyMap();
        this.successCount = this.successEntities.size();
        this.failureCount = this.failures.size();
    }

    public static <S, T> BatchTransitionResult<S, T> of(int totalCount, List<T> successEntities, Map<T, StateMachineException> failures) {
        return new BatchTransitionResult<>(totalCount, successEntities, failures);
    }

    public static <S, T> BatchTransitionResult<S, T> empty() {
        return new BatchTransitionResult<>(0, Collections.emptyList(), Collections.emptyMap());
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public List<T> getSuccessEntities() {
        return successEntities;
    }

    public Map<T, StateMachineException> getFailures() {
        return failures;
    }

    public List<T> getFailureEntities() {
        return Collections.unmodifiableList(new ArrayList<>(failures.keySet()));
    }

    public boolean isAllSuccess() {
        return failureCount == 0 && totalCount > 0;
    }

    public boolean hasFailures() {
        return failureCount > 0;
    }

    @Override
    public String toString() {
        return "BatchTransitionResult{" +
                "totalCount=" + totalCount +
                ", successCount=" + successCount +
                ", failureCount=" + failureCount +
                '}';
    }
}
