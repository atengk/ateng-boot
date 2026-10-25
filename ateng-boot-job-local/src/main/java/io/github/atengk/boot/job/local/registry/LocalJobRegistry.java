package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.lock.JobClusterLockProvider;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.JobMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单机定时任务注册中心与调度运行时持有者
 * <p>
 * 纳管任务运行时映射、两级元数据与日志存储 SPI、生命周期激活调度与优雅停机排空。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobRegistry implements DisposableBean, ApplicationEventPublisherAware {

    private static final Logger log = LoggerFactory.getLogger(LocalJobRegistry.class);

    private final ConcurrentMap<String, ScheduledJobTask> taskMap = new ConcurrentHashMap<>();
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AsyncTaskExecutor workerExecutor;
    private final JobMetadataRepository metadataRepository;
    private final JobLogStorageProvider logStorageProvider;
    private final JobClusterLockProvider clusterLockProvider;
    private final JobLocalProperties properties;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ApplicationEventPublisher eventPublisher;

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler) {
        this(taskScheduler, null, null, null, null, null);
    }

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler, AsyncTaskExecutor workerExecutor) {
        this(taskScheduler, workerExecutor, null, null, null, null);
    }

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobMetadataRepository metadataRepository,
                            JobLogStorageProvider logStorageProvider,
                            JobLocalProperties properties) {
        this(taskScheduler, workerExecutor, metadataRepository, logStorageProvider, null, properties);
    }

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobMetadataRepository metadataRepository,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            JobLocalProperties properties) {
        this.taskScheduler = taskScheduler;
        this.workerExecutor = workerExecutor;
        this.metadataRepository = metadataRepository;
        this.logStorageProvider = logStorageProvider;
        this.clusterLockProvider = clusterLockProvider;
        this.properties = properties;
    }

    /**
     * 注册一个任务
     *
     * @param definition 任务定义
     * @param handler    任务处理器
     * @return 注册后的受管任务
     */
    public ScheduledJobTask register(JobDefinition definition, JobHandler handler) {
        String jobName = definition.getJobName();
        if (taskMap.containsKey(jobName)) {
            throw new IllegalArgumentException("Duplicate job registration detected for job name: " + jobName);
        }

        // 1. preferDatabaseConfig 状态仲裁：若持久层已存在该任务配置，优先继承持久化状态与周期
        if (metadataRepository != null && (properties == null || properties.isPreferDatabaseConfig())) {
            Optional<JobDefinition> persistedOpt = metadataRepository.findByJobName(jobName);
            if (persistedOpt.isPresent()) {
                JobDefinition persisted = persistedOpt.get();
                if (persisted.getStatus() != null) {
                    definition.setStatus(persisted.getStatus());
                    definition.setEnabled(persisted.getStatus() == JobStatus.RUNNING);
                }
                if (persisted.getCron() != null && !persisted.getCron().isBlank()) {
                    definition.setCron(persisted.getCron());
                    definition.setTriggerType(TriggerType.CRON);
                }
            }
        }

        // 2. 持久化存储元数据
        if (metadataRepository != null) {
            metadataRepository.save(definition);
        }

        ScheduledJobTask task = new ScheduledJobTask(definition, handler, taskScheduler, workerExecutor,
                logStorageProvider, clusterLockProvider, eventPublisher);
        taskMap.put(jobName, task);

        // 3. 仅在已启动（started == true）且任务状态为 RUNNING 时调度
        if (started.get() && task.getStatus() == JobStatus.RUNNING && taskScheduler != null) {
            scheduleTask(task);
        }
        log.info("Registered local job [{}] with trigger type [{}] (started={}, status={})",
                jobName, definition.getTriggerType(), started.get(), task.getStatus());
        return task;
    }

    /**
     * 激活并启动所有已注册且状态为 RUNNING 的受管任务
     */
    public void startAll() {
        if (started.compareAndSet(false, true)) {
            log.info("Activating all registered local jobs on task scheduler (job count: {})", taskMap.size());
            for (ScheduledJobTask task : taskMap.values()) {
                if (task.getStatus() == JobStatus.RUNNING && taskScheduler != null) {
                    scheduleTask(task);
                }
            }
        }
    }

    /**
     * 优雅停止所有任务，取消未来调度并在指定超时内等待正在运行中的任务排空
     *
     * @param awaitPeriod 优雅停机等待超时
     */
    public void stopAll(Duration awaitPeriod) {
        started.set(false);
        log.info("Stopping all local jobs, canceling future triggers (job count: {})", taskMap.size());
        for (ScheduledJobTask task : taskMap.values()) {
            cancelSchedule(task);
        }

        long deadline = System.currentTimeMillis() + (awaitPeriod != null ? awaitPeriod.toMillis() : 30000L);
        boolean loggedWait = false;
        while (System.currentTimeMillis() < deadline) {
            boolean anyExecuting = false;
            for (ScheduledJobTask task : taskMap.values()) {
                if (task.isExecuting()) {
                    anyExecuting = true;
                    break;
                }
            }
            if (!anyExecuting) {
                log.info("All active local jobs drained cleanly");
                return;
            }
            if (!loggedWait) {
                log.info("Awaiting running local jobs to complete within {}...", awaitPeriod);
                loggedWait = true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for local jobs to complete", e);
                break;
            }
        }
        log.warn("Some local jobs did not complete within await period {}", awaitPeriod);
    }

    /**
     * 将任务调度到底层线程调度器
     */
    protected void scheduleTask(ScheduledJobTask task) {
        cancelSchedule(task);
        Trigger trigger = task.getTrigger();
        if (trigger != null && taskScheduler != null) {
            ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
            task.setScheduledFuture(future);
            task.setStatus(JobStatus.RUNNING);
            log.debug("Scheduled job [{}] onto task scheduler", task.getDefinition().getJobName());
        }
    }

    /**
     * 取消底层线程调度
     */
    protected void cancelSchedule(ScheduledJobTask task) {
        ScheduledFuture<?> future = task.getScheduledFuture();
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            task.setScheduledFuture(null);
        }
    }

    /**
     * 暂停任务
     *
     * @param jobName 任务名称
     * @return 是否暂停成功
     */
    public boolean pause(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        cancelSchedule(task);
        task.setStatus(JobStatus.PAUSED);
        if (metadataRepository != null) {
            metadataRepository.updateStatus(jobName, JobStatus.PAUSED);
        }
        log.info("Paused local job [{}]", jobName);
        return true;
    }

    /**
     * 恢复任务调度
     *
     * @param jobName 任务名称
     * @return 是否恢复成功
     */
    public boolean resume(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        if (task.getStatus() == JobStatus.PAUSED) {
            task.setStatus(JobStatus.RUNNING);
            if (started.get() && taskScheduler != null) {
                scheduleTask(task);
            }
            if (metadataRepository != null) {
                metadataRepository.updateStatus(jobName, JobStatus.RUNNING);
            }
            log.info("Resumed local job [{}]", jobName);
            return true;
        }
        return false;
    }

    /**
     * 立即手动触发执行一次
     *
     * @param jobName 任务名称
     * @param params  业务入参
     * @return 执行产物结果
     */
    public JobResult trigger(String jobName, Map<String, Object> params) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return JobResult.failure("Job not found: " + jobName);
        }
        return task.triggerOnce(params);
    }

    /**
     * 动态修改 Cron 周期表达式
     *
     * @param jobName 任务名称
     * @param newCron 新的 Cron 表达式
     * @return 是否更新并重新调度成功
     */
    public boolean updateCron(String jobName, String newCron) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        cancelSchedule(task);
        task.getDefinition().setCron(newCron);
        task.getDefinition().setTriggerType(TriggerType.CRON);
        task.setTrigger(new CronTrigger(newCron));
        if (metadataRepository != null) {
            metadataRepository.updateCron(jobName, newCron);
        }
        if (started.get() && task.getStatus() == JobStatus.RUNNING && taskScheduler != null) {
            scheduleTask(task);
        }
        log.info("Updated Cron expression for local job [{}] to [{}]", jobName, newCron);
        return true;
    }

    /**
     * 注销并移除任务
     *
     * @param jobName 任务名称
     * @return 是否注销成功
     */
    public boolean remove(String jobName) {
        ScheduledJobTask task = taskMap.remove(jobName);
        if (task != null) {
            cancelSchedule(task);
            task.setStatus(JobStatus.STOPPED);
            if (metadataRepository != null) {
                metadataRepository.delete(jobName);
            }
            log.info("Removed local job [{}]", jobName);
            return true;
        }
        return false;
    }

    /**
     * 获取单个任务信息视图
     *
     * @param jobName 任务名称
     * @return JobInfo 或 null
     */
    public JobInfo getJobInfo(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        return task != null ? task.toJobInfo() : null;
    }

    /**
     * 获取所有任务信息清单
     *
     * @return 任务清单列表
     */
    public List<JobInfo> listJobInfos() {
        List<JobInfo> list = new ArrayList<>(taskMap.size());
        for (ScheduledJobTask task : taskMap.values()) {
            list.add(task.toJobInfo());
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 获取所有任务包装器
     *
     * @return 任务 Map
     */
    public Map<String, ScheduledJobTask> getTaskMap() {
        return Collections.unmodifiableMap(taskMap);
    }

    /**
     * 查询指定任务最近的执行历史记录
     *
     * @param jobName 任务名称
     * @param limit   最大查询条数
     * @return 执行记录列表
     */
    public List<JobExecutionRecord> listRecentLogs(String jobName, int limit) {
        if (logStorageProvider == null) {
            return Collections.emptyList();
        }
        return logStorageProvider.listLogs(jobName, limit);
    }

    /**
     * 滚动清理超出保留时长的历史执行日志
     *
     * @param retentionDuration 最长保留时长
     * @return 实际清理的记录数
     */
    public int cleanExpiredLogs(Duration retentionDuration) {
        if (logStorageProvider == null) {
            return 0;
        }
        return logStorageProvider.cleanExpiredLogs(retentionDuration);
    }

    public JobMetadataRepository getMetadataRepository() {
        return metadataRepository;
    }

    public JobLogStorageProvider getLogStorageProvider() {
        return logStorageProvider;
    }

    public JobClusterLockProvider getClusterLockProvider() {
        return clusterLockProvider;
    }

    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    public ApplicationEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public void destroy() {
        log.info("Shutting down LocalJobRegistry (count: {})", taskMap.size());
        stopAll(Duration.ofSeconds(5));
        for (ScheduledJobTask task : taskMap.values()) {
            task.setStatus(JobStatus.STOPPED);
        }
        taskMap.clear();
    }
}
