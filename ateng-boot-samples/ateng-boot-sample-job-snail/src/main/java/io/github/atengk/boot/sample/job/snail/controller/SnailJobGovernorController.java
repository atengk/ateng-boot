package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.sample.job.snail.vo.GovernorStatusVO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * SnailJob 节点级任务运行时动态治理领域控制器
 *
 * <p>提供单节点维度的任务内存临时静默 (pause)、恢复 (resume)、冒号分级继承感知与状态巡检端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-snail/governor")
@RequiredArgsConstructor
public class SnailJobGovernorController {

    private final SnailJobRuntimeGovernor governor;

    /**
     * 单节点动态暂停指定调度或切片任务 (支持冒号分级，如 billSettlementJob:splitTask)
     *
     * @param jobName 任务名称（支持冒号分级路径）
     * @return 节点治理状态视图
     */
    @PostMapping("/pause/{jobName}")
    public Result<GovernorStatusVO> pauseJob(
            @PathVariable @NotBlank(message = "任务名称 jobName 不能为空") String jobName) {
        boolean success = governor.pause(jobName);
        log.info("[SnailJobGovernorController] 动态暂停任务: {}, 结果: {}", jobName, success);
        return Result.success(buildStatusVO(jobName, "PAUSE", success));
    }

    /**
     * 单节点动态恢复指定调度或切片任务
     *
     * @param jobName 任务名称
     * @return 节点治理状态视图
     */
    @PostMapping("/resume/{jobName}")
    public Result<GovernorStatusVO> resumeJob(
            @PathVariable @NotBlank(message = "任务名称 jobName 不能为空") String jobName) {
        boolean success = governor.resume(jobName);
        log.info("[SnailJobGovernorController] 动态恢复任务: {}, 结果: {}", jobName, success);
        return Result.success(buildStatusVO(jobName, "RESUME", success));
    }

    /**
     * 一键恢复当前节点所有被暂停的任务
     *
     * @return 节点治理状态视图
     */
    @PostMapping("/resume-all")
    public Result<GovernorStatusVO> resumeAll() {
        governor.resumeAll();
        log.info("[SnailJobGovernorController] 一键恢复当前节点所有任务");
        return Result.success(buildStatusVO("ALL", "RESUME_ALL", true));
    }

    /**
     * 查询当前节点已处于暂停状态的任务清单与计数
     *
     * @return 节点治理状态视图
     */
    @GetMapping("/paused-list")
    public Result<GovernorStatusVO> getPausedList() {
        return Result.success(buildStatusVO("QUERY", "QUERY", true));
    }

    private GovernorStatusVO buildStatusVO(String targetJob, String action, boolean success) {
        return GovernorStatusVO.builder()
                .targetJob(targetJob)
                .action(action)
                .success(success)
                .pausedCount(governor.getPausedCount())
                .pausedJobs(new ArrayList<>(governor.getPausedHandlers()))
                .build();
    }
}
