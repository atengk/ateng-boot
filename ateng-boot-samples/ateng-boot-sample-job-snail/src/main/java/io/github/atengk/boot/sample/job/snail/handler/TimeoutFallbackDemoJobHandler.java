package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务超时看门狗硬中断与就地降级补偿演练执行器
 *
 * <p>演示 {@code @AtengSnailJob} 的超时监控与降级补偿闭环：
 * <ul>
 *     <li>主方法声明超时阈值 {@code timeout = "80ms"}，模拟执行耗时阻塞；</li>
 *     <li>本地硬看门狗触发线程中断，自动路由至同 Bean 的降级补偿方法 {@code fallbackMethod}；</li>
 *     <li>降级方法安全接收原执行参数并完成应急业务补偿，向服务端返回成功，防止任务重试雪崩。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Component
public class TimeoutFallbackDemoJobHandler {

    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicBoolean fallbackExecuted = new AtomicBoolean(false);

    /**
     * 超时中断演练任务：模拟耗时操作超过看门狗阈值
     *
     * @param args 任务入参
     * @return 正常执行结果
     * @throws InterruptedException 线程被看门狗中断时抛出
     */
    @JobExecutor(name = "timeoutFallbackJob")
    @AtengSnailJob(timeout = "80ms", fallbackMethod = "executeFallbackCompensation")
    public ExecuteResult executeWithTimeout(JobArgs args) throws InterruptedException {
        executionCount.incrementAndGet();
        log.info("[TimeoutFallbackDemoJobHandler] 开始执行耗时任务 (模拟执行 500ms)...");
        Thread.sleep(500);
        return ExecuteResult.success("Normal execution completed unexpectedly");
    }

    /**
     * 同 Bean 就地降级补偿方法
     *
     * @param args 原任务执行上下文形参
     * @return 降级补偿执行结果
     */
    public ExecuteResult executeFallbackCompensation(JobArgs args) {
        fallbackExecuted.set(true);
        String params = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "default";
        log.warn("[TimeoutFallbackDemoJobHandler] 触发超时就地降级补偿逻辑, params: {}", params);
        return ExecuteResult.success("Fallback compensation executed successfully: " + params);
    }

    public boolean isFallbackExecuted() {
        return fallbackExecuted.get();
    }

    public int getExecutionCount() {
        return executionCount.get();
    }

    public void reset() {
        executionCount.set(0);
        fallbackExecuted.set(false);
    }
}
