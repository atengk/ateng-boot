package io.github.atengk.boot.ai.graph.parallel;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.TypedKey;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 并行扇出-汇聚认知状态图强类型状态契约基类
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class ParallelState extends BaseGraphState {

    /**
     * 结构化分支失败信息记录
     *
     * @param branchName     分支标识
     * @param errorType      错误分类（TIMEOUT / EXCEPTION / INTERRUPTED / CANCELLED）
     * @param message        错误摘要描述
     * @param exceptionClass 原始异常类全限定名
     */
    public record BranchError(
            String branchName,
            String errorType,
            String message,
            String exceptionClass
    ) implements Serializable {
    }

    /**
     * 各成功并行分支产出字典（key 为分支名，value 为分支产出）
     */
    public static final TypedKey<Map<String, Object>> BRANCH_RESULTS =
            TypedKey.of("branchResults", new TypeReference<Map<String, Object>>() {});

    /**
     * BEST_EFFORT 降级模式下记录的各失败/超时分支结构化错误字典
     */
    public static final TypedKey<Map<String, BranchError>> BRANCH_ERRORS =
            TypedKey.of("branchErrors", new TypeReference<Map<String, BranchError>>() {});

    /**
     * 已成功完成的分支名称列表
     */
    public static final TypedKey<List<String>> COMPLETED_BRANCHES =
            TypedKey.of("completedBranches", new TypeReference<List<String>>() {});

    /**
     * 发生异常或超时的分支名称列表
     */
    public static final TypedKey<List<String>> FAILED_BRANCHES =
            TypedKey.of("failedBranches", new TypeReference<List<String>>() {});

    /**
     * 汇聚裁决产生的最终文本或 JSON 输出
     */
    public static final TypedKey<String> FINAL_OUTPUT = TypedKey.of("finalOutput", String.class);

    /**
     * 是否已完成扇出-汇聚全链路
     */
    public static final TypedKey<Boolean> IS_COMPLETED = TypedKey.of("isCompleted", Boolean.class);

    public ParallelState() {
        this(new LinkedHashMap<>());
    }

    public ParallelState(Map<String, Object> initData) {
        super(initData != null ? new LinkedHashMap<>(initData) : new LinkedHashMap<>());
    }

    public Map<String, Object> getBranchResults() {
        return get(BRANCH_RESULTS).orElseGet(Collections::emptyMap);
    }

    public void setBranchResults(Map<String, Object> branchResults) {
        set(BRANCH_RESULTS, branchResults);
    }

    public Map<String, BranchError> getBranchErrors() {
        return get(BRANCH_ERRORS).orElseGet(Collections::emptyMap);
    }

    public void setBranchErrors(Map<String, BranchError> branchErrors) {
        set(BRANCH_ERRORS, branchErrors);
    }

    public List<String> getCompletedBranches() {
        return get(COMPLETED_BRANCHES).orElseGet(Collections::emptyList);
    }

    public List<String> getFailedBranches() {
        return get(FAILED_BRANCHES).orElseGet(Collections::emptyList);
    }

    public String getFinalOutput() {
        return get(FINAL_OUTPUT, "");
    }

    public void setFinalOutput(String finalOutput) {
        set(FINAL_OUTPUT, finalOutput);
    }

    public boolean isCompleted() {
        return Boolean.TRUE.equals(get(IS_COMPLETED, false));
    }

    public void setCompleted(boolean completed) {
        set(IS_COMPLETED, completed);
    }
}
