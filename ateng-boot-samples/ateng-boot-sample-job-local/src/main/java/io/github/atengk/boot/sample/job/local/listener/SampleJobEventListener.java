package io.github.atengk.boot.sample.job.local.listener;

import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.event.JobEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobStartingEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 示例定时任务事件监听器，用于审计和全真验证领域事件捕获
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Component
public class SampleJobEventListener {

    private static final Logger log = LoggerFactory.getLogger(SampleJobEventListener.class);

    private final List<JobEvent> capturedEvents = new CopyOnWriteArrayList<>();

    @EventListener
    public void onJobStarting(JobStartingEvent event) {
        log.info("Domain Event [JobStartingEvent]: jobName={}, traceId={}", event.getJobName(), event.getTraceId());
        capturedEvents.add(event);
    }

    @EventListener
    public void onJobSuccess(JobSuccessEvent event) {
        log.info("Domain Event [JobSuccessEvent]: jobName={}, duration={}ms, result={}",
                event.getJobName(), event.getDurationMs(), event.getResult());
        capturedEvents.add(event);
    }

    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        log.warn("Domain Event [JobFailedEvent]: jobName={}, error={}", event.getJobName(), event.getErrorMsg());
        capturedEvents.add(event);
    }

    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        log.warn("Domain Event [JobTimeoutEvent]: jobName={}, timeout={}", event.getJobName(), event.getTimeout());
        capturedEvents.add(event);
    }

    @EventListener
    public void onJobDiscarded(JobDiscardedEvent event) {
        log.warn("Domain Event [JobDiscardedEvent]: jobName={}", event.getJobName());
        capturedEvents.add(event);
    }

    public List<JobEvent> getCapturedEvents() {
        return capturedEvents;
    }

    public void clearEvents() {
        capturedEvents.clear();
    }
}
