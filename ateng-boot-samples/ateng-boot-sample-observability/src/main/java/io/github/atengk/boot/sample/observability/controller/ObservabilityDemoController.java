package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * 可观测性场景演示 Controller
 * 提供受管线程池异步任务调度接口，驱动 Prometheus 活跃线程与队列度量指标变化
 *
 * @author Ateng
 * @since 2026-10-22
 */
@RestController
@RequestMapping("/api/demo")
public class ObservabilityDemoController {

    private final ThreadPoolTaskExecutor sampleTaskExecutor;

    public ObservabilityDemoController(@Qualifier("sampleTaskExecutor") ThreadPoolTaskExecutor sampleTaskExecutor) {
        this.sampleTaskExecutor = sampleTaskExecutor;
    }

    /**
     * 触发异步计算任务，展示受管线程池并发执行与度量指标抓取
     */
    @GetMapping("/execute-task")
    public Result<String> executeTask() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, sampleTaskExecutor);

        return Result.success("任务已提交至受管线程池 sampleTaskExecutor");
    }
}
