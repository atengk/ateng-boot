package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.observability.event.HealthProbeDegradedEvent;
import io.github.atengk.boot.observability.event.HealthProbeRecoveredEvent;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 生产级受管健康探针防假死抽象基类
 * <p>
 * 为全量子系统健康探针提供异步超时切断与状态降级防卫，将具体探针探测逻辑隔离在独立受管线程池中执行。
 * 在下游物理中间件发生网络闪断、IO 挂起或假死时，依据强类型超时阈值（默认 2s）实施硬切断并返回 UNKNOWN/TIMEOUT 降级状态，
 * 坚决杜绝探针无限挂起拖垮 Actuator 线程池，保障 Kubernetes 存活与就绪检查（Liveness/Readiness）的高可用与稳定性。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public abstract class AbstractSafeHealthIndicator implements HealthIndicator, ApplicationEventPublisherAware, BeanFactoryAware {

    /**
     * 探针安全探测默认超时时间（2 秒）
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 容器内专属受管健康探针线程池 Bean 名称
     */
    public static final String DEFAULT_EXECUTOR_BEAN_NAME = "atengHealthProbeExecutor";

    @Getter
    private final Duration timeout;

    @Getter
    private final Duration cacheTtl;

    @Getter
    private final Duration degradedEventCooldown;

    private final boolean explicitExecutor;

    @Getter
    private volatile Executor executor;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock cacheLock = new ReentrantLock();
    private volatile CachedHealthSnapshot cachedSnapshot;
    private boolean degraded;
    private long firstDegradedAtNanos;
    private long lastDegradedEventAtNanos;
    private String lastDegradedStatus;
    private long consecutiveFailures;

    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (!this.explicitExecutor && beanFactory != null && beanFactory.containsBean(DEFAULT_EXECUTOR_BEAN_NAME)) {
            try {
                this.executor = beanFactory.getBean(DEFAULT_EXECUTOR_BEAN_NAME, Executor.class);
            } catch (BeansException ex) {
                log.debug("未能绑定容器受管健康探针线程池 [{}]，继续使用保底执行器: {}", DEFAULT_EXECUTOR_BEAN_NAME, ex.getMessage());
            }
        }
    }

    /**
     * 获取当前探针对应的组件名称（如 redis, kafka, storage 等，子类可覆写）
     */
    protected String getComponentName() {
        return getClass().getSimpleName();
    }

    /**
     * 默认构造器（使用默认 2s 超时阈值与公共工作线程池）
     */
    protected AbstractSafeHealthIndicator() {
        this(DEFAULT_TIMEOUT, null);
    }

    /**
     * 指定超时阈值构造器
     *
     * @param timeout 探测超时时长
     */
    protected AbstractSafeHealthIndicator(Duration timeout) {
        this(timeout, null);
    }

    /**
     * 基于配置属性对象的便捷构造器
     *
     * @param properties 可观测性全局配置属性
     */
    protected AbstractSafeHealthIndicator(ObservabilityProperties properties) {
        this(resolveTimeout(properties), null, resolveCacheTtl(properties), resolveDegradedEventCooldown(properties));
    }

    /**
     * 基于配置属性对象与受管执行器的构造器
     *
     * @param properties 可观测性全局配置属性
     * @param executor   受管异步线程池执行器
     */
    protected AbstractSafeHealthIndicator(ObservabilityProperties properties, Executor executor) {
        this(resolveTimeout(properties), executor, resolveCacheTtl(properties), resolveDegradedEventCooldown(properties));
    }

    /**
     * 全属性构造器
     *
     * @param timeout  探测超时时长（非正数或空时保底为 2 秒）
     * @param executor 受管异步线程池执行器（为空时回退至 ForkJoinPool.commonPool()）
     */
    protected AbstractSafeHealthIndicator(Duration timeout, Executor executor) {
        this(timeout, executor, Duration.ZERO, Duration.ZERO);
    }

    /**
     * 完整配置属性构造器
     *
     * @param timeout               探测超时时长（非正数或空时保底为 2 秒）
     * @param executor              受管异步线程池执行器（为空时回退至 ForkJoinPool.commonPool()）
     * @param cacheTtl              健康结果短效缓存有效期（非正数或空时关闭缓存）
     * @param degradedEventCooldown 连续降级事件冷却间隔（非正数或空时不抑制）
     */
    protected AbstractSafeHealthIndicator(
            Duration timeout,
            Executor executor,
            Duration cacheTtl,
            Duration degradedEventCooldown) {
        this.timeout = (timeout != null && !timeout.isNegative() && !timeout.isZero()) ? timeout : DEFAULT_TIMEOUT;
        this.cacheTtl = normalizeNonNegativeDuration(cacheTtl);
        this.degradedEventCooldown = normalizeNonNegativeDuration(degradedEventCooldown);
        this.explicitExecutor = (executor != null);
        this.executor = this.explicitExecutor ? executor : ForkJoinPool.commonPool();
    }

    /**
     * 解析配置属性中的超时时长保底值
     */
    private static Duration resolveTimeout(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null && properties.getHealth().getTimeout() != null) {
            return properties.getHealth().getTimeout();
        }
        return DEFAULT_TIMEOUT;
    }

    private static Duration resolveCacheTtl(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null) {
            return properties.getHealth().getCacheTtl();
        }
        return Duration.ZERO;
    }

    private static Duration resolveDegradedEventCooldown(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null) {
            return properties.getHealth().getDegradedEventCooldown();
        }
        return Duration.ZERO;
    }

    private static Duration normalizeNonNegativeDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return Duration.ZERO;
        }
        return duration;
    }

    /**
     * 复位探针故障状态机、累计失败计数并失效短效缓存快照（供运维干预或测试隔离调用）
     */
    public void resetProbeState() {
        cacheLock.lock();
        try {
            this.cachedSnapshot = null;
            stateLock.lock();
            try {
                this.degraded = false;
                this.firstDegradedAtNanos = 0L;
                this.lastDegradedEventAtNanos = 0L;
                this.lastDegradedStatus = null;
                this.consecutiveFailures = 0L;
            } finally {
                if (stateLock.isHeldByCurrentThread()) {
                    stateLock.unlock();
                }
            }
        } finally {
            if (cacheLock.isHeldByCurrentThread()) {
                cacheLock.unlock();
            }
        }
    }

    @Override
    public Health health() {
        if (this.cacheTtl.isZero()) {
            return executeProbe();
        }

        CachedHealthSnapshot snapshot = this.cachedSnapshot;
        if (snapshot != null && System.nanoTime() < snapshot.expireAtNanos()) {
            return snapshot.health();
        }

        cacheLock.lock();
        try {
            snapshot = this.cachedSnapshot;
            if (snapshot != null && System.nanoTime() < snapshot.expireAtNanos()) {
                return snapshot.health();
            }
            Health freshHealth = executeProbe();
            this.cachedSnapshot = new CachedHealthSnapshot(freshHealth, System.nanoTime() + this.cacheTtl.toNanos());
            return freshHealth;
        } finally {
            if (cacheLock.isHeldByCurrentThread()) {
                cacheLock.unlock();
            }
        }
    }

    /**
     * 执行单次异步超时受控的健康探测并驱动升降级状态机
     */
    private Health executeProbe() {
        long startTime = System.currentTimeMillis();
        Executor targetExecutor = (this.executor != null) ? this.executor : ForkJoinPool.commonPool();
        CompletableFuture<ProbeOutcome> future = CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> {
            Health.Builder builder = new Health.Builder();
            try {
                doCheck(builder);
                Health health = builder.build();
                String degradedReason = Status.DOWN.equals(health.getStatus()) ? "Probe reported DOWN status" : null;
                return new ProbeOutcome(health, null, degradedReason);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                // 线程被主线程超时切断中断，由主线程负责发布 TIMEOUT 事件，工作线程避免重复发布次生事件
                return new ProbeOutcome(Health.unknown()
                        .withDetail("status", "CANCELLED")
                        .withDetail("reason", "Probe execution cancelled by timeout")
                        .build(), null, null);
            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted() || isInterruptedCause(ex)) {
                    // 若线程已被中断取消，不再重复发布次生 DOWN 事件
                    return new ProbeOutcome(Health.unknown()
                            .withDetail("status", "CANCELLED")
                            .withDetail("reason", "Probe execution cancelled by timeout")
                            .build(), null, null);
                }
                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                log.warn("健康探针 [{}] 执行检测异常: {}", getClass().getSimpleName(), ex.getMessage(), ex);
                return new ProbeOutcome(Health.down(ex).withDetail("reason", reason).build(), ex, reason);
            }
        }), targetExecutor);

        try {
            ProbeOutcome outcome = future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
            Health health = outcome.health();
            if (Status.UP.equals(health.getStatus())) {
                recordUpTransition();
            } else if (Status.DOWN.equals(health.getStatus())) {
                long costMs = System.currentTimeMillis() - startTime;
                recordDegradedTransition("DOWN", costMs, outcome.degradedReason(), outcome.errorCause());
            }
            return health;
        } catch (TimeoutException ex) {
            future.cancel(true);
            long costMs = System.currentTimeMillis() - startTime;
            log.warn("健康探针 [{}] 执行检测超时（阈值: {}），触发防假死降级", getClass().getSimpleName(), this.timeout);
            recordDegradedTransition("TIMEOUT", costMs, "Probe execution timed out", ex);
            return Health.unknown()
                    .withDetail("status", "TIMEOUT")
                    .withDetail("timeout", this.timeout.toString())
                    .withDetail("reason", "Probe execution timed out")
                    .build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            long costMs = System.currentTimeMillis() - startTime;
            log.warn("健康探针 [{}] 执行检测被中断", getClass().getSimpleName());
            recordDegradedTransition("DOWN", costMs, "Probe execution interrupted", ex);
            return Health.down(ex)
                    .withDetail("reason", "Probe execution interrupted")
                    .build();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            long costMs = System.currentTimeMillis() - startTime;
            String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            log.warn("健康探针 [{}] 异步执行异常: {}", getClass().getSimpleName(), cause.getMessage(), cause);
            recordDegradedTransition("DOWN", costMs, reason, cause);
            return Health.down(cause)
                    .withDetail("reason", reason)
                    .build();
        }
    }

    private void recordDegradedTransition(String status, long costMs, String reason, Throwable cause) {
        boolean shouldPublish;
        long nowNanos = System.nanoTime();
        stateLock.lock();
        try {
            if (!this.degraded) {
                this.degraded = true;
                this.firstDegradedAtNanos = nowNanos;
                this.lastDegradedStatus = status;
                this.consecutiveFailures = 1L;
                this.lastDegradedEventAtNanos = nowNanos;
                shouldPublish = true;
            } else {
                this.consecutiveFailures++;
                this.lastDegradedStatus = status;
                if (this.degradedEventCooldown.isZero()
                        || (nowNanos - this.lastDegradedEventAtNanos) >= this.degradedEventCooldown.toNanos()) {
                    this.lastDegradedEventAtNanos = nowNanos;
                    shouldPublish = true;
                } else {
                    shouldPublish = false;
                }
            }
        } finally {
            if (stateLock.isHeldByCurrentThread()) {
                stateLock.unlock();
            }
        }
        if (shouldPublish) {
            publishDegradedEvent(status, costMs, reason, cause);
        }
    }

    private void recordUpTransition() {
        RecoverySnapshot recovery = null;
        long nowNanos = System.nanoTime();
        stateLock.lock();
        try {
            if (this.degraded) {
                long downtimeMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nowNanos - this.firstDegradedAtNanos));
                recovery = new RecoverySnapshot(this.lastDegradedStatus, downtimeMs, this.consecutiveFailures);
                this.degraded = false;
                this.firstDegradedAtNanos = 0L;
                this.lastDegradedEventAtNanos = 0L;
                this.lastDegradedStatus = null;
                this.consecutiveFailures = 0L;
            }
        } finally {
            if (stateLock.isHeldByCurrentThread()) {
                stateLock.unlock();
            }
        }
        if (recovery != null) {
            publishRecoveredEvent(recovery.previousStatus(), recovery.downtimeMs(), recovery.consecutiveFailures());
        }
    }

    /**
     * 发布健康探针恢复自愈领域事件
     *
     * @param previousStatus      恢复前最近一次故障状态
     * @param downtimeMs          持续故障时长（毫秒）
     * @param consecutiveFailures 故障期间累计连续失败次数
     */
    protected void publishRecoveredEvent(String previousStatus, long downtimeMs, long consecutiveFailures) {
        if (this.eventPublisher != null) {
            try {
                this.eventPublisher.publishEvent(new HealthProbeRecoveredEvent(
                        this,
                        getClass().getSimpleName(),
                        getComponentName(),
                        previousStatus,
                        downtimeMs,
                        consecutiveFailures
                ));
            } catch (Exception e) {
                log.warn("发布健康探针恢复事件失败 [{}]: {}", getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private record ProbeOutcome(Health health, Throwable errorCause, String degradedReason) {
    }

    private record RecoverySnapshot(String previousStatus, long downtimeMs, long consecutiveFailures) {
    }

    private record CachedHealthSnapshot(Health health, long expireAtNanos) {
    }

    /**
     * 发布健康探针降级或故障领域事件
     *
     * @param status 状态（TIMEOUT, DOWN 等）
     * @param costMs 探测耗时（毫秒）
     * @param reason 降级原因
     * @param cause  根因异常
     */
    protected void publishDegradedEvent(String status, long costMs, String reason, Throwable cause) {
        if (this.eventPublisher != null) {
            try {
                this.eventPublisher.publishEvent(new HealthProbeDegradedEvent(
                        this,
                        getClass().getSimpleName(),
                        getComponentName(),
                        status,
                        costMs,
                        reason,
                        cause
                ));
            } catch (Exception e) {
                log.warn("发布健康探针降级事件失败 [{}]: {}", getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 执行具体子系统的物理健康探测（由各子模块探针实现）
     *
     * @param builder 健康状态构建器
     * @throws Exception 探测过程中抛出的任何异常
     */
    protected abstract void doCheck(Health.Builder builder) throws Exception;

    private static boolean isInterruptedCause(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
