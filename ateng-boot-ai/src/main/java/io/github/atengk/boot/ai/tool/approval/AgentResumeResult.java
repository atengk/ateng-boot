package io.github.atengk.boot.ai.tool.approval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体高危审批恢复续跑强类型响应结果
 * <p>
 * 封装高危工具重放执行结果、智能体续跑最终生成答复或级联二次审批工单标识。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResumeResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前被核准的审批工单唯一业务流水标识
     */
    private String ticketId;

    /**
     * 智能体是否已完全结束并得出最终结论
     * true: 已产出终态最终答复; false: 续跑后又触发了级联高危工具调用待新审批
     */
    private boolean completed;

    /**
     * 智能体完成续跑后的最终响应文本（仅当 completed=true 时有值）
     */
    private String finalResponse;

    /**
     * 若续跑后紧接着触发了下一个高危工具，记录新生成的待审批工单标识（仅当 completed=false 时有值）
     */
    private String nextPendingTicketId;

    /**
     * 目标高危工具方法安全重放执行的返回值对象
     */
    private Object toolExecutionResult;

    /**
     * 工具重放执行耗时 (毫秒)
     */
    private long duration;

    /**
     * 异常或失败错误描述信息
     */
    private String errorMessage;

    /**
     * 判定当前审批重放是否成功
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return errorMessage == null;
    }

    /**
     * 构造终态完成结果
     *
     * @param ticketId      当前审批工单标识
     * @param finalResponse 智能体最终回答
     * @param toolResult    工具执行返回值
     * @param duration      耗时 (毫秒)
     * @return 智能体续跑结果
     */
    public static AgentResumeResult completed(String ticketId, String finalResponse, Object toolResult, long duration) {
        return AgentResumeResult.builder()
                .ticketId(ticketId)
                .completed(true)
                .finalResponse(finalResponse)
                .toolExecutionResult(toolResult)
                .duration(duration)
                .build();
    }

    /**
     * 构造级联审批挂起结果
     *
     * @param ticketId     当前审批工单标识
     * @param nextTicketId 下一个新触发的高危工单标识
     * @param toolResult   当前工具执行返回值
     * @param duration     耗时 (毫秒)
     * @return 智能体续跑结果
     */
    public static AgentResumeResult pendingNext(String ticketId, String nextTicketId, Object toolResult, long duration) {
        return AgentResumeResult.builder()
                .ticketId(ticketId)
                .completed(false)
                .nextPendingTicketId(nextTicketId)
                .toolExecutionResult(toolResult)
                .duration(duration)
                .build();
    }

    /**
     * 构造失败结果
     *
     * @param ticketId 当前审批工单标识
     * @param error    错误信息
     * @param duration 耗时 (毫秒)
     * @return 智能体续跑结果
     */
    public static AgentResumeResult failed(String ticketId, String error, long duration) {
        return AgentResumeResult.builder()
                .ticketId(ticketId)
                .completed(false)
                .errorMessage(error)
                .duration(duration)
                .build();
    }
}
