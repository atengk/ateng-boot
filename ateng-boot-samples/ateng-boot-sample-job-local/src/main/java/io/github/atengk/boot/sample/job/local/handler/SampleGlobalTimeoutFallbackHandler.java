package io.github.atengk.boot.sample.job.local.handler;

import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.fallback.LocalJobTimeoutFallbackHandler;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 示例全局超时降级补偿处理器 SPI 实现
 * <p>
 * 演示 {@link LocalJobTimeoutFallbackHandler} 全局兜底扩展点：当未声明 {@code @LocalJob(fallbackMethod = ...)}
 * 的任务发生硬超时且启用全局降级开关时，由此组件统一接管返回兜底结果；未开启全局兜底时透传原始 {@link JobTimeoutException}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class SampleGlobalTimeoutFallbackHandler implements LocalJobTimeoutFallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(SampleGlobalTimeoutFallbackHandler.class);

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    @Override
    public JobResult handleTimeoutFallback(JobContext context, JobTimeoutException exception) {
        boolean paramEnabled = context != null && context.getParams() != null
                && Boolean.parseBoolean(String.valueOf(context.getParams().get("enableGlobalFallback")));
        if (!enabled.get() && !paramEnabled) {
            throw exception;
        }
        log.warn("SampleGlobalTimeoutFallbackHandler 捕获任务 [{}] 全局超时降级: timeout={}",
                context.getJobName(), exception.getTimeout());
        return JobResult.failure("任务 [" + context.getJobName() + "] 超时已触发全局 SPI 降级兜底");
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }
}
