package io.github.atengk.boot.ai.graph.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 主管 Agent 规划与调度决策契约对象
 * <p>
 * 支持大模型通过结构化输出（如 {@code AiTemplate.chatForObject}）直接反序列化生成，
 * 封装下一步指派的专工名称、具体执行指令、当前全局规划进度以及是否已达成终态判定。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorDecision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 下一步指派的专工 Worker 标识名称（如 "researcher", "coder", "reviewer"，若完成可为 "FINISH" 或 null）
     */
    private String nextWorker;

    /**
     * 下发给专工的具体执行指令或任务 Prompt
     */
    private String instructions;

    /**
     * 全局任务是否已执行完毕
     */
    private boolean isFinished;

    /**
     * 当前主管提炼的整体推进规划或备忘信息
     */
    private String currentPlan;

    /**
     * 最终汇总结论或对用户的综合回答（当 isFinished=true 时提供）
     */
    private String finalOutput;

    /**
     * 便捷构建任务已完成决策
     *
     * @param finalOutput 最终汇总结论
     * @return 决策对象
     */
    public static SupervisorDecision finish(String finalOutput) {
        return SupervisorDecision.builder()
                .isFinished(true)
                .nextWorker("FINISH")
                .finalOutput(finalOutput)
                .build();
    }

    /**
     * 便捷构建继续指派专工决策
     *
     * @param nextWorker 下一步专工名称
     * @param instructions 下发指令
     * @return 决策对象
     */
    public static SupervisorDecision next(String nextWorker, String instructions) {
        return SupervisorDecision.builder()
                .isFinished(false)
                .nextWorker(nextWorker)
                .instructions(instructions)
                .build();
    }

    /**
     * 便捷构建继续指派专工并更新规划备忘决策
     *
     * @param nextWorker 下一步专工名称
     * @param instructions 下发指令
     * @param currentPlan 当前整体规划
     * @return 决策对象
     */
    public static SupervisorDecision next(String nextWorker, String instructions, String currentPlan) {
        return SupervisorDecision.builder()
                .isFinished(false)
                .nextWorker(nextWorker)
                .instructions(instructions)
                .currentPlan(currentPlan)
                .build();
    }
}
