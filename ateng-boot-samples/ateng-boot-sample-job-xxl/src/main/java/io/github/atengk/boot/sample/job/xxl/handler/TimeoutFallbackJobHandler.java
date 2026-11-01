package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地看门狗超时中断与就地降级补偿任务样例
 *
 * <p>通过 {@link AtengXxlJob} 配置毫秒级硬看门狗超时时限与同 Bean 本地降级补偿方法。
 * 当任务执行超时时，看门狗向执行线程发送中断信号，框架捕获后自动路由至指定的 fallback 方法，
 * 执行对账补偿并返回 200 SUCCESS 优雅自愈。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Component
public class TimeoutFallbackJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutFallbackJobHandler.class);

    private final AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
    private volatile CountDownLatch fallbackLatch = new CountDownLatch(1);

    /**
     * 超时降级任务：设置 200ms 硬看门狗超时时限，超时后自动回调 onTimeoutFallback
     */
    @XxlJob("timeoutFallbackDemoJob")
    @AtengXxlJob(value = "timeoutFallbackDemoJob", timeout = "200ms", fallback = "onTimeoutFallback")
    public void executeSlowJob() {
        log.info(">>>>>> [TimeoutFallbackJobHandler] 开始执行慢任务，预期被看门狗中断...");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            log.warn(">>>>>> [TimeoutFallbackJobHandler] 捕获到硬看门狗线程中断信号并优雅退出！");
            XxlJobHelper.handleSuccess("任务超时被看门狗中断，触发就地降级补偿: timeoutFallbackDemoJob");
            return;
        }
        XxlJobHelper.handleSuccess("未超时正常执行完成");
    }

    /**
     * 就地降级补偿方法：支持自动注入 {@link JobExecutionMetadata} 与已耗时 {@link Duration}
     *
     * @param metadata 任务执行元数据
     * @param elapsed  超时经历时长
     */
    public void onTimeoutFallback(JobExecutionMetadata metadata, Duration elapsed) {
        log.warn(">>>>>> [TimeoutFallbackJobHandler] 触发就地降级补偿回调! 任务: {}, 耗时: {}ms",
                metadata.getJobName(), elapsed.toMillis());
        fallbackInvoked.set(true);
        fallbackLatch.countDown();
    }

    public boolean isFallbackInvoked() {
        return fallbackInvoked.get();
    }

    public CountDownLatch getFallbackLatch() {
        return fallbackLatch;
    }

    public void reset() {
        fallbackInvoked.set(false);
        fallbackLatch = new CountDownLatch(1);
    }
}
