package io.github.atengk.boot.sms.delay;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.properties.SmsDelayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 基于 Spring 受管 ThreadPoolTaskScheduler 的内存延迟与定时短信调度器
 * 支持 MDC/User/Tenant/SmsContextHolder 跨线程完整上下文透传、停机优雅排空及运行期确定性任务取消
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class MemorySmsDelayScheduler extends AbstractSmsDelayScheduler {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ConcurrentHashMap<String, PendingTaskHandle> pendingTasks = new ConcurrentHashMap<>();

    public MemorySmsDelayScheduler() {
        this(new SmsDelayProperties(), Clock.systemDefaultZone());
    }

    public MemorySmsDelayScheduler(SmsDelayProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    public MemorySmsDelayScheduler(SmsDelayProperties properties, Clock clock) {
        super(properties, clock);
        this.taskScheduler = createInitializedScheduler("ateng-sms-delay-", this.properties);
    }

    @Override
    public SmsScheduledReceipt scheduleSingle(SmsRequest request, Instant triggerAt, String channelHint) {
        BusinessAssert.notNull(request, "单条发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        String taskId = IdUtil.fastSimpleUUID();
        scheduleInternal(taskId, triggerAt, () -> executeSingleDispatch(taskId, request));
        return new SmsScheduledReceipt(taskId, triggerAt, channelHint, false, request.getPhones().size(), this);
    }

    @Override
    public SmsScheduledReceipt scheduleBatch(BatchSmsRequest request, Instant triggerAt, String channelHint) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        String taskId = IdUtil.fastSimpleUUID();
        scheduleInternal(taskId, triggerAt, () -> executeBatchDispatch(taskId, request));
        return new SmsScheduledReceipt(taskId, triggerAt, channelHint, true, request.getPhones().size(), this);
    }

    private void scheduleInternal(String taskId, Instant triggerAt, Supplier<SmsScheduledStatus> action) {
        PendingTaskHandle handle = new PendingTaskHandle();
        pendingTasks.put(taskId, handle);

        // 显式捕获调用线程的 SmsContextHolder（同时确保静态快照提供者已注册），并在任务结束严格还原
        final String capturedSmsChannel = SmsContextHolder.getChannel();
        Runnable wrappedTask = AsyncContext.wrap(() -> {
            if (!handle.status.compareAndSet(SmsScheduledStatus.PENDING, SmsScheduledStatus.RUNNING)) {
                return;
            }
            String previousSmsChannel = SmsContextHolder.getChannel();
            SmsScheduledStatus finalStatus = SmsScheduledStatus.FAILED;
            try {
                if (capturedSmsChannel != null) {
                    SmsContextHolder.setChannel(capturedSmsChannel);
                } else {
                    SmsContextHolder.clear();
                }
                finalStatus = action.get();
            } finally {
                if (previousSmsChannel != null) {
                    SmsContextHolder.setChannel(previousSmsChannel);
                } else {
                    SmsContextHolder.clear();
                }
                handle.status.set(finalStatus);
                pendingTasks.remove(taskId);
                recordStatus(taskId, finalStatus);
            }
        });

        ScheduledFuture<?> future;
        try {
            future = taskScheduler.schedule(wrappedTask, triggerAt);
        } catch (RuntimeException ex) {
            pendingTasks.remove(taskId);
            recordStatus(taskId, SmsScheduledStatus.FAILED);
            throw ex;
        }
        handle.future = future;
        // 防御竞争窗口：若任务提交前已被其他线程迅速取消，补偿取消刚返回的 Future
        if (handle.status.get() == SmsScheduledStatus.CANCELLED && future != null) {
            future.cancel(false);
        }
    }

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        String key = taskId.trim();
        PendingTaskHandle handle = pendingTasks.get(key);
        if (handle == null) {
            return false;
        }
        if (handle.status.compareAndSet(SmsScheduledStatus.PENDING, SmsScheduledStatus.CANCELLED)) {
            ScheduledFuture<?> future = handle.future;
            if (future != null) {
                future.cancel(false);
            }
            pendingTasks.remove(key);
            recordStatus(key, SmsScheduledStatus.CANCELLED);
            if (log.isDebugEnabled()) {
                log.debug("[MemorySmsDelayScheduler] 已取消待触发延迟短信任务 [{}]", key);
            }
            return true;
        }
        return false;
    }

    @Override
    public SmsScheduledStatus getStatus(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        String key = taskId.trim();
        PendingTaskHandle active = pendingTasks.get(key);
        if (active != null) {
            return active.status.get();
        }
        return super.getStatus(key);
    }

    /**
     * 获取当前尚处于待触发队列中的活跃任务数
     *
     * @return 待触发任务数
     */
    public int getPendingCount() {
        return pendingTasks.size();
    }

    @Override
    public void destroy() {
        taskScheduler.shutdown();
    }

    private static final class PendingTaskHandle {
        private final AtomicReference<SmsScheduledStatus> status = new AtomicReference<>(SmsScheduledStatus.PENDING);
        private volatile ScheduledFuture<?> future;
    }
}
