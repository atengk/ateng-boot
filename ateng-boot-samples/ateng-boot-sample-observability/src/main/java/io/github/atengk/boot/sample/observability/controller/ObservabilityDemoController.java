package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.observability.annotation.ObservedMetric;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 可观测性场景演示 Controller
 * 提供受管线程池异步任务调度、业务指标编程式与声明式打点，以及线程池拒绝场景演示接口
 *
 * @author Ateng
 * @since 2026-10-22
 */
@RestController
@RequestMapping("/api/demo")
public class ObservabilityDemoController {

    private final ThreadPoolTaskExecutor sampleTaskExecutor;
    private final MetricsTemplate metricsTemplate;

    public ObservabilityDemoController(
            @Qualifier("sampleTaskExecutor") ThreadPoolTaskExecutor sampleTaskExecutor,
            MetricsTemplate metricsTemplate) {
        this.sampleTaskExecutor = sampleTaskExecutor;
        this.metricsTemplate = metricsTemplate;
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

    /**
     * 编程式指标打点演示接口
     */
    @GetMapping("/metrics/programmatic")
    public Result<String> programmaticMetric() {
        metricsTemplate.increment("sample.orders.created", 1.0, "channel", "app");
        metricsTemplate.summary("sample.orders.amount", 99.5, "channel", "app");
        metricsTemplate.record("sample.orders.process.time", Duration.ofMillis(25), "channel", "app");
        return Result.success("编程式指标打点成功");
    }

    /**
     * 声明式注解 @ObservedMetric 指标打点演示接口
     */
    @GetMapping("/metrics/declarative")
    @ObservedMetric(value = "sample.demo.observed", tags = {"tier", "api"}, percentiles = {0.95, 0.99})
    public Result<String> declarativeMetric() {
        return Result.success("声明式指标记录成功");
    }

    /**
     * 触发线程池打满拒绝演示接口
     */
    @GetMapping("/trigger-rejection")
    public Result<String> triggerRejection() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            // 提交 70 个阻塞任务，打满 core(4) + queue(50) + max(8) 触发拒绝
            for (int i = 0; i < 70; i++) {
                sampleTaskExecutor.execute(() -> {
                    try {
                        latch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } catch (RejectedExecutionException ignored) {
            // 预期触发拒绝
        } finally {
            latch.countDown();
        }
        return Result.success("已触发线程池拒绝");
    }
}
