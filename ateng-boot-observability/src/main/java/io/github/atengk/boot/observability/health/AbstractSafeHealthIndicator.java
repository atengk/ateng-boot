package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
public abstract class AbstractSafeHealthIndicator implements HealthIndicator {

    /**
     * 探针安全探测默认超时时间（2 秒）
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    @Getter
    private final Duration timeout;

    @Getter
    private final Executor executor;

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
        this(resolveTimeout(properties), null);
    }

    /**
     * 基于配置属性对象与受管执行器的构造器
     *
     * @param properties 可观测性全局配置属性
     * @param executor   受管异步线程池执行器
     */
    protected AbstractSafeHealthIndicator(ObservabilityProperties properties, Executor executor) {
        this(resolveTimeout(properties), executor);
    }

    /**
     * 全属性构造器
     *
     * @param timeout  探测超时时长（非正数或空时保底为 2 秒）
     * @param executor 受管异步线程池执行器（为空时回退至 ForkJoinPool.commonPool()）
     */
    protected AbstractSafeHealthIndicator(Duration timeout, Executor executor) {
        this.timeout = (timeout != null && !timeout.isNegative() && !timeout.isZero()) ? timeout : DEFAULT_TIMEOUT;
        this.executor = (executor != null) ? executor : ForkJoinPool.commonPool();
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

    @Override
    public Health health() {
        CompletableFuture<Health> future = CompletableFuture.supplyAsync(() -> {
            Health.Builder builder = new Health.Builder();
            try {
                doCheck(builder);
                return builder.build();
            } catch (Exception ex) {
                log.warn("健康探针 [{}] 执行检测异常: {}", getClass().getSimpleName(), ex.getMessage(), ex);
                return Health.down(ex)
                        .withDetail("reason", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                        .build();
            }
        }, this.executor);

        try {
            return future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("健康探针 [{}] 执行检测超时（阈值: {}），触发防假死降级", getClass().getSimpleName(), this.timeout);
            return Health.unknown()
                    .withDetail("status", "TIMEOUT")
                    .withDetail("timeout", this.timeout.toString())
                    .withDetail("reason", "Probe execution timed out")
                    .build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("健康探针 [{}] 执行检测被中断", getClass().getSimpleName());
            return Health.down(ex)
                    .withDetail("reason", "Probe execution interrupted")
                    .build();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.warn("健康探针 [{}] 异步执行异常: {}", getClass().getSimpleName(), cause.getMessage(), cause);
            return Health.down(cause)
                    .withDetail("reason", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())
                    .build();
        }
    }

    /**
     * 执行具体子系统的物理健康探测（由各子模块探针实现）
     *
     * @param builder 健康状态构建器
     * @throws Exception 探测过程中抛出的任何异常
     */
    protected abstract void doCheck(Health.Builder builder) throws Exception;
}
