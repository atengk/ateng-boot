package io.github.atengk.boot.sample.job.xxl.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.xxl.governor.JobRuntimeGovernor;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 节点级任务运行时治理控制器
 *
 * <p>演示单微服务节点内部受管任务执行状态的动态治理，支持在单个实例维度对特定任务实施临时静默与恢复，
 * 防止局部故障扩散，随 Pod 重启自复位。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-xxl/governor")
@RequiredArgsConstructor
public class XxlJobGovernorController {

    private final JobRuntimeGovernor governor;

    /**
     * 单节点动态静默指定任务处理器
     *
     * @param handler 任务处理器名称
     * @return 是否成功静默
     */
    @PostMapping("/pause/{handler}")
    public Result<Boolean> pauseHandler(@PathVariable @NotBlank(message = "任务处理器标识不能为空") String handler) {
        log.info("[XxlJobGovernorController] 动态暂停单节点任务: handler={}", handler);
        governor.pause(handler);
        return Result.success(governor.isPaused(handler));
    }

    /**
     * 单节点动态恢复指定任务处理器
     *
     * @param handler 任务处理器名称
     * @return 是否已成功恢复运行
     */
    @PostMapping("/resume/{handler}")
    public Result<Boolean> resumeHandler(@PathVariable @NotBlank(message = "任务处理器标识不能为空") String handler) {
        log.info("[XxlJobGovernorController] 动态恢复单节点任务: handler={}", handler);
        governor.resume(handler);
        return Result.success(!governor.isPaused(handler));
    }

    /**
     * 查询当前节点内存中的已静默任务处理器清单
     *
     * @return 已静默任务处理器集合
     */
    @GetMapping("/paused-handlers")
    public Result<Set<String>> getPausedHandlers() {
        Set<String> pausedList = governor.getPausedHandlers();
        log.info("[XxlJobGovernorController] 巡检当前节点静默任务清单: count={}", pausedList.size());
        return Result.success(pausedList);
    }
}
