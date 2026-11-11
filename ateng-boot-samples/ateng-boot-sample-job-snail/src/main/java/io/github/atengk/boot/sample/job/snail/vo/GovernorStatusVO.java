package io.github.atengk.boot.sample.job.snail.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 节点级任务运行时治理状态视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernorStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 操作目标任务标识 (如 "billSettlementJob" 或 "billSettlementJob:splitTask")
     */
    private String targetJob;

    /**
     * 治理操作动作 (PAUSE / RESUME / RESUME_ALL)
     */
    private String action;

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 当前节点被暂停的任务总数
     */
    private Integer pausedCount;

    /**
     * 当前节点处于暂停状态的任务清单
     */
    private List<String> pausedJobs;
}
