package io.github.atengk.boot.job.local.template;

import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link LocalJobTemplate} 默认开箱即用实现
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class DefaultLocalJobTemplate implements LocalJobTemplate {

    private final LocalJobRegistry registry;

    public DefaultLocalJobTemplate(LocalJobRegistry registry) {
        Assert.notNull(registry, "LocalJobRegistry must not be null");
        this.registry = registry;
    }

    @Override
    public boolean pause(String jobName) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.pause(jobName);
    }

    @Override
    public boolean resume(String jobName) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.resume(jobName);
    }

    @Override
    public JobResult trigger(String jobName, Map<String, Object> params) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.trigger(jobName, params);
    }

    @Override
    public boolean updateCron(String jobName, String cronExpression) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.hasText(cronExpression, "cronExpression must not be empty");
        return registry.updateCron(jobName, cronExpression);
    }

    @Override
    public boolean register(JobDefinition definition, JobHandler handler) {
        Assert.notNull(definition, "JobDefinition must not be null");
        Assert.notNull(handler, "JobHandler must not be null");
        return registry.register(definition, handler) != null;
    }

    @Override
    public boolean registerCron(String jobName, String cron, Runnable runnable) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.hasText(cron, "cron must not be empty");
        Assert.notNull(runnable, "runnable must not be null");

        JobDefinition definition = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(TriggerType.CRON)
                .cron(cron)
                .strategy(OverlappingStrategy.DISCARD)
                .enabled(true)
                .build();
        return register(definition, context -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public boolean registerFixedRate(String jobName, Duration fixedRate, Runnable runnable) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.notNull(fixedRate, "fixedRate must not be null");
        Assert.notNull(runnable, "runnable must not be null");

        JobDefinition definition = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(TriggerType.FIXED_RATE)
                .fixedRate(fixedRate)
                .strategy(OverlappingStrategy.DISCARD)
                .enabled(true)
                .build();
        return register(definition, context -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public boolean registerFixedDelay(String jobName, Duration fixedDelay, Runnable runnable) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.notNull(fixedDelay, "fixedDelay must not be null");
        Assert.notNull(runnable, "runnable must not be null");

        JobDefinition definition = JobDefinition.builder()
                .jobName(jobName)
                .triggerType(TriggerType.FIXED_DELAY)
                .fixedDelay(fixedDelay)
                .strategy(OverlappingStrategy.DISCARD)
                .enabled(true)
                .build();
        return register(definition, context -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public boolean remove(String jobName) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.remove(jobName);
    }

    @Override
    public JobInfo getJob(String jobName) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.getJobInfo(jobName);
    }

    @Override
    public List<JobInfo> listJobs() {
        return registry.listJobInfos();
    }

    @Override
    public List<JobExecutionRecord> listRecentLogs(String jobName, int limit) {
        return Collections.emptyList();
    }
}
