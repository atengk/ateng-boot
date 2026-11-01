package io.github.atengk.boot.job.local.template;

import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(DefaultLocalJobTemplate.class);

    /**
     * 预演计算未来执行时间点的单次最大上限（防止过大入参触发 OOM）
     */
    public static final int MAX_PREVIEW_COUNT = 100;

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
    public boolean updateFixedRate(String jobName, Duration newFixedRate) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.notNull(newFixedRate, "newFixedRate must not be null");
        return registry.updateFixedRate(jobName, newFixedRate);
    }

    @Override
    public boolean updateFixedDelay(String jobName, Duration newFixedDelay) {
        Assert.hasText(jobName, "jobName must not be empty");
        Assert.notNull(newFixedDelay, "newFixedDelay must not be null");
        return registry.updateFixedDelay(jobName, newFixedDelay);
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
        return register(definition, adaptRunnable(runnable));
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
        return register(definition, adaptRunnable(runnable));
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
        return register(definition, adaptRunnable(runnable));
    }

    private static JobHandler adaptRunnable(Runnable runnable) {
        return context -> {
            runnable.run();
            return null;
        };
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
    public boolean exists(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        return registry.exists(jobName.trim());
    }

    @Override
    public boolean isExecuting(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return false;
        }
        return registry.isExecuting(jobName.trim());
    }

    @Override
    public boolean isValidCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return false;
        }
        return CronExpression.isValidExpression(cronExpression.trim());
    }

    @Override
    public List<Instant> getNextExecutionTimes(String cronExpression, int count, Instant fromTime) {
        if (!isValidCron(cronExpression) || count <= 0) {
            return Collections.emptyList();
        }
        int targetCount = Math.min(count, MAX_PREVIEW_COUNT);
        try {
            CronExpression cron = CronExpression.parse(cronExpression.trim());
            List<Instant> result = new ArrayList<>(targetCount);
            ZoneId zoneId = ZoneId.systemDefault();
            ZonedDateTime current = ZonedDateTime.ofInstant(fromTime != null ? fromTime : Instant.now(), zoneId);
            for (int i = 0; i < targetCount; i++) {
                ZonedDateTime next = cron.next(current);
                if (next == null) {
                    break;
                }
                result.add(next.toInstant());
                current = next;
            }
            return Collections.unmodifiableList(result);
        } catch (Exception ex) {
            log.warn("Failed to parse or calculate next execution times for cron [{}]: {}", cronExpression, ex.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<JobExecutionRecord> listRecentLogs(String jobName, int limit) {
        Assert.hasText(jobName, "jobName must not be empty");
        return registry.listRecentLogs(jobName, limit);
    }

    @Override
    public int cleanExpiredLogs(Duration retentionDuration) {
        if (retentionDuration == null || retentionDuration.isZero() || retentionDuration.isNegative()) {
            return 0;
        }
        return registry.cleanExpiredLogs(retentionDuration);
    }
}
