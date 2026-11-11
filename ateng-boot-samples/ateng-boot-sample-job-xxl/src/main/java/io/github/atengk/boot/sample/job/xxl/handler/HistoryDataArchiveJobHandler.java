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
 * 历史冷数据归档与本地看门狗超时就地降级补偿任务处理器
 *
 * <p>通过 {@link AtengXxlJob} 配置毫秒级硬看门狗超时时限与同 Bean 本地降级补偿方法。
 * 当历史数据归档扫描超时时，看门狗向执行线程发送中断信号，框架切面捕获后自动路由至指定的 fallback 方法，
 * 执行释放分布式锁并标记批次待重试，返回 200 SUCCESS 优雅自愈。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class HistoryDataArchiveJobHandler {

    private static final Logger log = LoggerFactory.getLogger(HistoryDataArchiveJobHandler.class);

    private final AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
    private volatile CountDownLatch fallbackLatch = new CountDownLatch(1);

    /**
     * 历史冷数据归档任务：设置 200ms 硬看门狗超时时限，超时后自动回调 onArchiveTimeoutFallback
     */
    @XxlJob("historyDataArchiveJob")
    @AtengXxlJob(value = "historyDataArchiveJob", timeout = "200ms", fallback = "onArchiveTimeoutFallback")
    public void executeHistoryDataArchive() {
        log.info(">>>>>> [HistoryDataArchiveJobHandler] 开始执行历史冷数据大批量归档扫描...");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            log.warn(">>>>>> [HistoryDataArchiveJobHandler] 捕获到硬看门狗线程中断信号并优雅退出！");
            XxlJobHelper.handleSuccess("归档任务超时被看门狗中断，触发就地降级补偿: historyDataArchiveJob");
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
    public void onArchiveTimeoutFallback(JobExecutionMetadata metadata, Duration elapsed) {
        log.warn(">>>>>> [HistoryDataArchiveJobHandler] 触发就地降级补偿回调! 任务: {}, 耗时: {}ms",
                metadata.getJobName(), elapsed.toMillis());
        fallbackInvoked.set(true);
        fallbackLatch.countDown();
    }

    /**
     * 兼容别名任务：超时降级任务
     */
    @XxlJob("timeoutFallbackDemoJob")
    @AtengXxlJob(value = "timeoutFallbackDemoJob", timeout = "200ms", fallback = "onArchiveTimeoutFallback")
    public void executeSlowJob() {
        executeHistoryDataArchive();
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
