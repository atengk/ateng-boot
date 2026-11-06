package io.github.atengk.boot.ai.graph.supervisor;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.TypedKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主管-专工分层认知状态图强类型状态契约模型
 * <p>
 * 继承自 {@link BaseGraphState}，集中收敛全局任务目标、整体规划推进、下一步待执行专工、
 * 历史专工产出结果集、主管决策轮次以及任务完成状态与最终结果。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class SupervisorState extends BaseGraphState {

    /**
     * 全局任务总体目标
     */
    public static final TypedKey<String> TASK_OBJECTIVE = TypedKey.of("taskObjective", String.class);

    /**
     * 当前整体规划与进度备忘
     */
    public static final TypedKey<String> CURRENT_PLAN = TypedKey.of("currentPlan", String.class);

    /**
     * 下一步指派的专工 Worker 标识
     */
    public static final TypedKey<String> NEXT_WORKER = TypedKey.of("nextWorker", String.class);

    /**
     * 主管下发给专工的具体指令 Prompt
     */
    public static final TypedKey<String> INSTRUCTIONS = TypedKey.of("instructions", String.class);

    /**
     * 历史各专工执行产出汇总字典（键为专工名称，值为产出结果）
     */
    public static final TypedKey<Map<String, Object>> WORKER_RESULTS =
            TypedKey.of("workerResults", new TypeReference<Map<String, Object>>() {});

    /**
     * 主管决策流转轮次计数器
     */
    public static final TypedKey<Integer> TURN_COUNT = TypedKey.of("turnCount", Integer.class);

    /**
     * 任务是否已整体达成终态
     */
    public static final TypedKey<Boolean> IS_FINISHED = TypedKey.of("isFinished", Boolean.class);

    /**
     * 最终汇总结果输出
     */
    public static final TypedKey<String> FINAL_OUTPUT = TypedKey.of("finalOutput", String.class);

    /**
     * 无参缺省构造器
     */
    public SupervisorState() {
        this(Collections.emptyMap());
    }

    /**
     * 基于初始字典构造状态模型
     *
     * @param initData 初始状态字典
     */
    public SupervisorState(Map<String, Object> initData) {
        super(initData);
    }

    public String getTaskObjective() {
        return get(TASK_OBJECTIVE, "");
    }

    public void setTaskObjective(String taskObjective) {
        set(TASK_OBJECTIVE, taskObjective);
    }

    public String getCurrentPlan() {
        return get(CURRENT_PLAN, "");
    }

    public void setCurrentPlan(String currentPlan) {
        set(CURRENT_PLAN, currentPlan);
    }

    public String getNextWorker() {
        return get(NEXT_WORKER, "");
    }

    public void setNextWorker(String nextWorker) {
        set(NEXT_WORKER, nextWorker);
    }

    public String getInstructions() {
        return get(INSTRUCTIONS, "");
    }

    public void setInstructions(String instructions) {
        set(INSTRUCTIONS, instructions);
    }

    public Map<String, Object> getWorkerResults() {
        return get(WORKER_RESULTS).orElseGet(LinkedHashMap::new);
    }

    public void setWorkerResults(Map<String, Object> workerResults) {
        set(WORKER_RESULTS, workerResults != null ? workerResults : new LinkedHashMap<>());
    }

    public void addWorkerResult(String workerName, Object result) {
        Map<String, Object> results = new LinkedHashMap<>(getWorkerResults());
        results.put(workerName, result);
        set(WORKER_RESULTS, results);
    }

    public int getTurnCount() {
        return get(TURN_COUNT, 0);
    }

    public void setTurnCount(int turnCount) {
        set(TURN_COUNT, turnCount);
    }

    public boolean isFinished() {
        return get(IS_FINISHED, false);
    }

    public void setFinished(boolean finished) {
        set(IS_FINISHED, finished);
    }

    public String getFinalOutput() {
        return get(FINAL_OUTPUT, "");
    }

    public void setFinalOutput(String finalOutput) {
        set(FINAL_OUTPUT, finalOutput);
    }
}
