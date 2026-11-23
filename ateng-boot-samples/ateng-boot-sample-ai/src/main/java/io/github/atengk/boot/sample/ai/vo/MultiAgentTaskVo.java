package io.github.atengk.boot.sample.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 多智能体协同任务执行结果 VO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentTaskVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流执行会话标识
     */
    private String threadId;

    /**
     * 工作流名称
     */
    private String workflowName;

    /**
     * 状态机状态 (RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED)
     */
    private String status;

    /**
     * 全局任务目标
     */
    private String taskObjective;

    /**
     * 主管调度轮次计数
     */
    private int turnCount;

    /**
     * 当前整体推进规划
     */
    private String currentPlan;

    /**
     * 各专工产出结果字典
     */
    private Map<String, Object> workerResults;

    /**
     * 最终成果总结
     */
    private String finalOutput;

    /**
     * 任务是否已结束
     */
    @JsonProperty("isFinished")
    private boolean isFinished;

    /**
     * 从状态图快照视图转换构建 VO
     *
     * @param snapshot 状态图快照
     * @return 业务 VO 实体
     */
    @SuppressWarnings("unchecked")
    public static MultiAgentTaskVo from(StateSnapshotVo snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> state = snapshot.state();
        String taskObjective = (String) state.get(SupervisorState.TASK_OBJECTIVE.name());
        Integer turnCount = (Integer) state.get(SupervisorState.TURN_COUNT.name());
        String currentPlan = (String) state.get(SupervisorState.CURRENT_PLAN.name());
        Map<String, Object> workerResults = (Map<String, Object>) state.get(SupervisorState.WORKER_RESULTS.name());
        String finalOutput = (String) state.get(SupervisorState.FINAL_OUTPUT.name());
        Boolean isFinished = (Boolean) state.get(SupervisorState.IS_FINISHED.name());

        return MultiAgentTaskVo.builder()
                .threadId(snapshot.threadId())
                .workflowName(snapshot.workflowName())
                .status(snapshot.status() != null ? snapshot.status().name() : null)
                .taskObjective(taskObjective)
                .turnCount(turnCount != null ? turnCount : 0)
                .currentPlan(currentPlan)
                .workerResults(workerResults != null ? workerResults : Collections.emptyMap())
                .finalOutput(finalOutput)
                .isFinished(Boolean.TRUE.equals(isFinished))
                .build();
    }
}
