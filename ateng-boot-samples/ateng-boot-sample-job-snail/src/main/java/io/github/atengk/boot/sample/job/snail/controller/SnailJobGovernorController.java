package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.sample.job.snail.vo.GovernorStatusVO;
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
     */
    @PostMapping("/pause/{jobName}")
    public Result<GovernorStatusVO> pauseJob(@PathVariable String jobName) {
        boolean success = governor.pause(jobName);
        log.info("[SnailJobGovernorController] 动态暂停任务: {}, 结果: {}", jobName, success);
        GovernorStatusVO vo = GovernorStatusVO.builder()
                .targetJob(jobName)
                .action("PAUSE")
                .success(success)
                .pausedCount(governor.getPausedCount())
                .pausedJobs(new ArrayList<>(governor.getPausedHandlers()))
                .build();
        return Result.success(vo);
    }

    /**
     * 单节点动态恢复指定调度或切片任务
     */
    @PostMapping("/resume/{jobName}")
    public Result<GovernorStatusVO> resumeJob(@PathVariable String jobName) {
        boolean success = governor.resume(jobName);
        log.info("[SnailJobGovernorController] 动态恢复任务: {}, 结果: {}", jobName, success);
        GovernorStatusVO vo = GovernorStatusVO.builder()
                .targetJob(jobName)
                .action("RESUME")
                .success(success)
                .pausedCount(governor.getPausedCount())
                .pausedJobs(new ArrayList<>(governor.getPausedHandlers()))
                .build();
        return Result.success(vo);
    }

    /**
     * 一键恢复当前节点所有被暂停的任务
     */
    @PostMapping("/resume-all")
    public Result<GovernorStatusVO> resumeAll() {
        governor.resumeAll();
        log.info("[SnailJobGovernorController] 一键恢复当前节点所有任务");
        GovernorStatusVO vo = GovernorStatusVO.builder()
                .targetJob("ALL")
                .action("RESUME_ALL")
                .success(true)
                .pausedCount(governor.getPausedCount())
                .pausedJobs(new ArrayList<>(governor.getPausedHandlers()))
                .build();
        return Result.success(vo);
    }

    /**
     * 查询当前节点已处于暂停状态的任务清单与计数
     */
    @GetMapping("/paused-list")
    public Result<GovernorStatusVO> getPausedList() {
        GovernorStatusVO vo = GovernorStatusVO.builder()
                .targetJob("QUERY")
                .action("QUERY")
                .success(true)
                .pausedCount(governor.getPausedCount())
                .pausedJobs(new ArrayList<>(governor.getPausedHandlers()))
                .build();
        return Result.success(vo);
    }
}
