package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体高危审批恢复续跑视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResumeResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前被核准的审批工单唯一业务流水标识
     */
    private String ticketId;

    /**
     * 智能体是否已完全结束并得出最终结论
     */
    private boolean completed;

    /**
     * 智能体完成续跑后的最终响应文本
     */
    private String finalResponse;

    /**
     * 若续跑后紧接着触发了下一个高危工具，记录新生成的待审批工单标识
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
     * 是否执行成功
     */
    private boolean success;
}
