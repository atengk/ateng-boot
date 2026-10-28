package io.github.atengk.boot.datasource.template;

import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全库多租户批处理聚合执行结果
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
@ToString
public class BatchExecutionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成功执行项数量
     */
    private final int successCount;

    /**
     * 失败项详情列表
     */
    private final List<BatchFailure> failures;

    public BatchExecutionResult(int successCount, List<BatchFailure> failures) {
        this.successCount = Math.max(0, successCount);
        this.failures = failures != null ? Collections.unmodifiableList(new ArrayList<>(failures)) : Collections.emptyList();
    }

    /**
     * 获取批处理总处理项数
     *
     * @return 成功数与失败数之和
     */
    public int getTotalCount() {
        return successCount + failures.size();
    }

    /**
     * 获取失败项数量
     *
     * @return 失败数
     */
    public int getFailureCount() {
        return failures.size();
    }

    /**
     * 是否全部执行成功且无任何失败项
     *
     * @return true 表示全部成功；false 表示存在失败或未处理项
     */
    public boolean isAllSuccess() {
        return failures.isEmpty() && getTotalCount() > 0;
    }

    /**
     * 是否存在失败项
     *
     * @return true 表示存在失败项；false 表示零失败
     */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /**
     * 提取所有执行失败的目标标识列表（租户 ID 或数据源 Key）
     *
     * @return 失败目标列表
     */
    public List<String> getFailedTargets() {
        if (failures.isEmpty()) {
            return Collections.emptyList();
        }
        return failures.stream().map(BatchFailure::getTarget).toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int successCount = 0;
        private final List<BatchFailure> failures = new ArrayList<>();

        public Builder recordSuccess() {
            this.successCount++;
            return this;
        }

        public Builder recordFailure(String target, Throwable cause) {
            String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "Unknown execution error";
            this.failures.add(new BatchFailure(target, message, cause));
            return this;
        }

        public BatchExecutionResult build() {
            return new BatchExecutionResult(successCount, failures);
        }
    }
}
