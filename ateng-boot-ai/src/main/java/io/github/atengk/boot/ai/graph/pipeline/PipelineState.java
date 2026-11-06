package io.github.atengk.boot.ai.graph.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.TypedKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性顺序流水线强类型状态契约模型
 * <p>
 * 继承自 {@link BaseGraphState}，集中纳管流水线名称、当前执行阶段、历史阶段产出结果集、
 * 跳过阶段列表、已完成阶段列表、失败阶段以及最终汇总输出与完成标识。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class PipelineState extends BaseGraphState {

    /**
     * 流水线名称
     */
    public static final TypedKey<String> PIPELINE_NAME = TypedKey.of("pipelineName", String.class);

    /**
     * 当前正在执行或最近完成的阶段标识
     */
    public static final TypedKey<String> CURRENT_STAGE = TypedKey.of("currentStage", String.class);

    /**
     * 历史各阶段执行产出汇总字典（键为阶段名称，值为产出结果）
     */
    public static final TypedKey<Map<String, Object>> STAGE_RESULTS =
            TypedKey.of("stageResults", new TypeReference<Map<String, Object>>() {});

    /**
     * 已跳过的阶段名称列表
     */
    public static final TypedKey<List<String>> SKIPPED_STAGES =
            TypedKey.of("skippedStages", new TypeReference<List<String>>() {});

    /**
     * 已成功完成的阶段名称列表
     */
    public static final TypedKey<List<String>> COMPLETED_STAGES =
            TypedKey.of("completedStages", new TypeReference<List<String>>() {});

    /**
     * 发生异常熔断或中断的阶段标识
     */
    public static final TypedKey<String> FAILED_STAGE = TypedKey.of("failedStage", String.class);

    /**
     * 最终汇总结果输出
     */
    public static final TypedKey<String> FINAL_OUTPUT = TypedKey.of("finalOutput", String.class);

    /**
     * 流水线是否已整体达成终态
     */
    public static final TypedKey<Boolean> IS_COMPLETED = TypedKey.of("isCompleted", Boolean.class);

    /**
     * 无参缺省构造器
     */
    public PipelineState() {
        this(new LinkedHashMap<>());
    }

    /**
     * 基于初始字典构造状态模型
     *
     * @param initData 初始状态字典
     */
    public PipelineState(Map<String, Object> initData) {
        super(initData != null ? new LinkedHashMap<>(initData) : new LinkedHashMap<>());
    }

    public String getPipelineName() {
        return get(PIPELINE_NAME, "");
    }

    public void setPipelineName(String pipelineName) {
        set(PIPELINE_NAME, pipelineName);
    }

    public String getCurrentStage() {
        return get(CURRENT_STAGE, "");
    }

    public void setCurrentStage(String currentStage) {
        set(CURRENT_STAGE, currentStage);
    }

    public Map<String, Object> getStageResults() {
        return get(STAGE_RESULTS).orElseGet(LinkedHashMap::new);
    }

    public void setStageResults(Map<String, Object> stageResults) {
        set(STAGE_RESULTS, stageResults);
    }

    public List<String> getSkippedStages() {
        return get(SKIPPED_STAGES).orElseGet(Collections::emptyList);
    }

    public void setSkippedStages(List<String> skippedStages) {
        set(SKIPPED_STAGES, skippedStages);
    }

    public List<String> getCompletedStages() {
        return get(COMPLETED_STAGES).orElseGet(Collections::emptyList);
    }

    public void setCompletedStages(List<String> completedStages) {
        set(COMPLETED_STAGES, completedStages);
    }

    public String getFailedStage() {
        return get(FAILED_STAGE, "");
    }

    public void setFailedStage(String failedStage) {
        set(FAILED_STAGE, failedStage);
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
