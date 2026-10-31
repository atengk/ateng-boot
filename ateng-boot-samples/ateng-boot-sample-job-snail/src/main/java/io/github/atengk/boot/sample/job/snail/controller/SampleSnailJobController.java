package io.github.atengk.boot.sample.job.snail.controller;

import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.sample.job.snail.service.RetryDemoService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SnailJob 调度与重试样例演示接口
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/snail-job")
@RequiredArgsConstructor
public class SampleSnailJobController {

    private final SnailJobTestHelper testHelper;
    private final RetryDemoService retryDemoService;

    /**
     * 演示离线触发任务
     */
    @PostMapping("/trigger-offline")
    public Result<String> triggerOffline(@NotBlank(message = "任务名称不能为空") @RequestParam(name = "jobName", defaultValue = "simpleGreetingJob") String jobName) {
        SnailJobTestResult testResult = SnailJobTestHelper.triggerJob(jobName, null);
        if (testResult.isSuccess()) {
            String msg = testResult.result() instanceof ExecuteResult er ? er.getMessage() : String.valueOf(testResult.result());
            return Result.success("Job [" + jobName + "] executed successfully: " + msg);
        }
        return Result.failure("Job [" + jobName + "] failed: " + (testResult.throwable() != null ? testResult.throwable().getMessage() : "unknown"));
    }

    /**
     * 演示分布式协同重试
     */
    @PostMapping("/retry-order")
    public Result<String> retryOrder(@NotBlank(message = "订单单号不能为空") @RequestParam(name = "orderId", defaultValue = "ORDER-1001") String orderId) {
        String res = retryDemoService.payOrder(orderId, 99.9);
        return Result.success(res);
    }

    /**
     * 演示状态汇总
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        return Result.success(Map.of(
                "jobCount", testHelper.getRegisteredJobNames().size(),
                "retryCount", testHelper.getRegisteredRetryScenes().size(),
                "attemptCount", retryDemoService.getAttemptCount()
        ));
    }
}
