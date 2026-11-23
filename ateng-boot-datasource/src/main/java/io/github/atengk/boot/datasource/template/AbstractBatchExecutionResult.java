package io.github.atengk.boot.datasource.template;

import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批处理执行结果抽象基类（承载非泛型统计与失败详情契约，保障原始类型引用向后兼容）
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Getter
@ToString
public abstract class AbstractBatchExecutionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成功执行项数量
     */
    private final int successCount;

    /**
     * 失败项详情列表
     */
    private final List<BatchFailure> failures;

    AbstractBatchExecutionResult(int successCount, List<BatchFailure> failures) {
        this.successCount = Math.max(0, successCount);
        this.failures = failures != null
                ? Collections.unmodifiableList(new ArrayList<>(failures))
                : Collections.emptyList();
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
}
