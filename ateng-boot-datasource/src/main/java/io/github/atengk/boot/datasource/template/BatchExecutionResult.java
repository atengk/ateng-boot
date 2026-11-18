package io.github.atengk.boot.datasource.template;

import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全库多租户批处理聚合执行结果
 *
 * @param <T> 单目标计算返回值类型
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
@ToString(callSuper = true)
public class BatchExecutionResult<T> extends AbstractBatchExecutionResult {

    private static final long serialVersionUID = 1L;

    /**
     * 各成功执行目标的返回值映射表（按遍历顺序保留，支持合法 null 值）
     */
    private final Map<String, T> results;

    public BatchExecutionResult(int successCount, List<BatchFailure> failures) {
        this(successCount, failures, null);
    }

    public BatchExecutionResult(int successCount, List<BatchFailure> failures, Map<String, T> results) {
        super(successCount, failures);
        this.results = (results != null && !results.isEmpty())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(results))
                : Collections.emptyMap();
    }

    /**
     * 按目标标识获取批处理计算返回值
     *
     * @param target 目标标识（数据源 Key 或租户 ID）
     * @return 对应目标返回值；未命中或执行失败时返回 null
     */
    public T getResult(String target) {
        if (target == null) {
            return null;
        }
        return results.get(target);
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private int successCount = 0;
        private final List<BatchFailure> failures = new ArrayList<>();
        private final Map<String, T> results = new LinkedHashMap<>();

        public Builder<T> recordSuccess() {
            this.successCount++;
            return this;
        }

        public Builder<T> recordSuccess(String target, T value) {
            this.successCount++;
            if (target != null) {
                this.results.put(target, value);
            }
            return this;
        }

        public Builder<T> recordFailure(String target, Throwable cause) {
            String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "Unknown execution error";
            this.failures.add(new BatchFailure(target, message, cause));
            return this;
        }

        public BatchExecutionResult<T> build() {
            return new BatchExecutionResult<>(successCount, failures, results);
        }
    }
}
