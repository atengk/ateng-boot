package io.github.atengk.boot.sms.delay;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.properties.SmsDelayProperties;
import io.github.atengk.boot.sms.support.BoundedLruMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 受管可取消延迟与定时短信调度器抽象基类
 * 收敛调度线程池构建、分发器绑定、有界终态历史追踪与安全异常边界
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public abstract class AbstractSmsDelayScheduler implements SmsDelayScheduler, DisposableBean {

    @Getter
    protected final SmsDelayProperties properties;

    @Getter
    protected final Clock clock;

    protected final BoundedLruMap<String, SmsScheduledStatus> historyStatusMap;

    private volatile Consumer<SmsRequest> singleDispatcher;
    private volatile Consumer<BatchSmsRequest> batchDispatcher;

    protected AbstractSmsDelayScheduler(SmsDelayProperties properties, Clock clock) {
        this.properties = properties != null ? properties : new SmsDelayProperties();
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        int historyCap = this.properties.getMaxStatusHistory() > 0 ? this.properties.getMaxStatusHistory() : 10000;
        this.historyStatusMap = new BoundedLruMap<>(historyCap);
    }

    @Override
    public void bindDispatchers(Consumer<SmsRequest> singleDispatcher, Consumer<BatchSmsRequest> batchDispatcher) {
        this.singleDispatcher = singleDispatcher;
        this.batchDispatcher = batchDispatcher;
    }

    protected static ThreadPoolTaskScheduler createInitializedScheduler(String threadPrefix, SmsDelayProperties props) {
        int poolSize = props != null && props.getPoolSize() > 0 ? props.getPoolSize() : 2;
        Duration await = props != null && props.getAwaitTermination() != null && !props.getAwaitTermination().isNegative()
                ? props.getAwaitTermination()
                : Duration.ofSeconds(15);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadPrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds((int) Math.min(Integer.MAX_VALUE, await.toSeconds()));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(err -> log.error("[SmsDelayScheduler] 调度线程执行未捕获异常: {}", err.getMessage(), err));
        scheduler.initialize();
        return scheduler;
    }

    protected SmsScheduledStatus executeSingleDispatch(String taskId, SmsRequest request) {
        Consumer<SmsRequest> dispatcher = this.singleDispatcher;
        if (dispatcher == null) {
            log.warn("[SmsDelayScheduler] 单条延迟任务 [{}] 到点触发，但尚未绑定发信分发器", taskId);
            return SmsScheduledStatus.FAILED;
        }
        try {
            dispatcher.accept(request);
            return SmsScheduledStatus.COMPLETED;
        } catch (Exception ex) {
            log.warn("[SmsDelayScheduler] 单条延迟任务 [{}] 到点投递失败或被策略拦截 (目标号码: {}): {}",
                    taskId, DesensitizeUtil.mobilePhone(request.getPhone()), ex.getMessage());
            return SmsScheduledStatus.FAILED;
        }
    }

    protected SmsScheduledStatus executeBatchDispatch(String taskId, BatchSmsRequest request) {
        Consumer<BatchSmsRequest> dispatcher = this.batchDispatcher;
        if (dispatcher == null) {
            log.warn("[SmsDelayScheduler] 批量延迟任务 [{}] 到点触发，但尚未绑定发信分发器", taskId);
            return SmsScheduledStatus.FAILED;
        }
        try {
            dispatcher.accept(request);
            return SmsScheduledStatus.COMPLETED;
        } catch (Exception ex) {
            log.warn("[SmsDelayScheduler] 批量延迟任务 [{}] 到点投递失败或被策略拦截 (号码数: {}): {}",
                    taskId, request.getPhones().size(), ex.getMessage());
            return SmsScheduledStatus.FAILED;
        }
    }

    @Override
    public boolean isCancelled(String taskId) {
        return getStatus(taskId) == SmsScheduledStatus.CANCELLED;
    }

    @Override
    public boolean isDone(String taskId) {
        SmsScheduledStatus status = getStatus(taskId);
        return status == SmsScheduledStatus.COMPLETED
                || status == SmsScheduledStatus.CANCELLED
                || status == SmsScheduledStatus.FAILED;
    }

    protected void recordStatus(String taskId, SmsScheduledStatus status) {
        if (taskId != null && !taskId.isBlank() && status != null) {
            historyStatusMap.put(taskId.trim(), status, null);
        }
    }

    @Override
    public SmsScheduledStatus getStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return historyStatusMap.get(taskId.trim());
    }
}
