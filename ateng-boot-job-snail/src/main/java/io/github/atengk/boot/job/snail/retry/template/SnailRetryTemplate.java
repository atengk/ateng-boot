package io.github.atengk.boot.job.snail.retry.template;

import com.aizuda.snailjob.client.core.Report;
import com.aizuda.snailjob.client.core.intercepter.RetrySiteSnapshot;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryFailureTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;

/**
 * SnailJob 编程式分布式重试执行与手动上报对称门面
 *
 * <p>为应用层提供声明式注解之外的完整编程式重试能力：
 * <ul>
 *     <li><b>编程式闭包执行</b>：支持 {@link #execute(String, RetryCallback)} 与
 *     {@link #executeWithIdempotent(String, String, RetryCallback)}，自动编排四维快照、本地重试退避与异常转译；</li>
 *     <li><b>对称手动失败上报</b>：支持 {@link #reportManualRetry(String, String, Object[])}，
 *     在异步消费或脱机场景下直接将失败现场持久化至 SnailJob 服务端；</li>
 *     <li><b>严格因果链传递</b>：全链路受控拦截异常，完整保留原始 {@code cause} 转译为 {@link BusinessException}。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailRetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(SnailRetryTemplate.class);

    private static final String TRACE_ID_KEY = "traceId";
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BACKOFF_MS = 200L;

    private final ObjectProvider<Report> reportProvider;
    private final Report preferredReport;
    private final SnailJobProperties properties;
    private final SnailRetryContextStore contextStore;

    public SnailRetryTemplate() {
        this((ObjectProvider<Report>) null, null, null);
    }

    public SnailRetryTemplate(ObjectProvider<Report> reportProvider, SnailJobProperties properties) {
        this(reportProvider, properties, null);
    }

    public SnailRetryTemplate(
            ObjectProvider<Report> reportProvider,
            SnailJobProperties properties,
            SnailRetryContextStore contextStore) {
        this(reportProvider, null, properties, contextStore);
    }

    /**
     * 全参数构造器（支持显式指定优先 {@link Report} 实现，避免 {@code asyncReport}/{@code syncReport} 双 Bean 歧义）
     *
     * @param reportProvider  上报客户端提供者（可为 null）
     * @param preferredReport 显式优选的上报客户端实例（可为 null）
     * @param properties      SnailJob 配置属性
     * @param contextStore    重试上下文持久化存储
     */
    public SnailRetryTemplate(
            ObjectProvider<Report> reportProvider,
            Report preferredReport,
            SnailJobProperties properties,
            SnailRetryContextStore contextStore) {
        this.reportProvider = reportProvider;
        this.preferredReport = preferredReport;
        this.properties = properties;
        this.contextStore = contextStore != null ? contextStore : SnailRetryContextRegistry.getStore();
    }

    /**
     * 执行带自动重试保护的业务闭包（默认以场景名称生成幂等键）
     *
     * @param scene 业务重试场景标识
     * @param callback 业务回调
     * @param <T> 返回值泛型
     * @return 业务执行结果
     */
    public <T> T execute(String scene, RetryCallback<T> callback) {
        return executeWithIdempotent(scene, null, null, callback);
    }

    /**
     * 执行带业务唯一幂等键保护的重试闭包
     *
     * @param scene 业务重试场景标识
     * @param idempotentId 业务幂等标识（如订单号）
     * @param callback 业务回调
     * @param <T> 返回值泛型
     * @return 业务执行结果
     */
    public <T> T executeWithIdempotent(String scene, String idempotentId, RetryCallback<T> callback) {
        return executeWithIdempotent(scene, idempotentId, null, callback);
    }

    /**
     * 执行带完整幂等标识与业务维度的重试闭包
     *
     * @param scene 业务重试场景标识
     * @param idempotentId 业务幂等标识
     * @param bizNo 业务编号
     * @param callback 业务回调
     * @param <T> 返回值泛型
     * @return 业务执行结果
     */
    public <T> T executeWithIdempotent(String scene, String idempotentId, String bizNo, RetryCallback<T> callback) {
        Objects.requireNonNull(scene, "scene 场景标识不能为空");
        Objects.requireNonNull(callback, "callback 回调逻辑不能为空");

        String actualIdempotentId = StringUtils.hasText(idempotentId) ? idempotentId : IdUtil.fastSimpleUUID();
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        ensureTraceId();

        // 捕获调用方四维快照并登记
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        String contextKey = scene + ":" + actualIdempotentId;
        this.contextStore.put(contextKey, snapshot);

        int maxAttempts = resolveLocalMaxAttempts();
        Duration backoff = resolveLocalBackoff();
        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(maxAttempts)
                .delay(backoff)
                .multiplier(1.5)
                .maxDelay(backoff.multipliedBy(Math.max(maxAttempts, 1)))
                .onRetry((attempt, error) -> log.warn("SnailRetryTemplate 场景 [{}] 第 {} 次尝试失败 (幂等键: {}): {}",
                        scene, attempt, actualIdempotentId, error.getMessage()))
                .build();

        boolean keepContextForRemote = false;
        try {
            T result = RetryUtil.execute(() -> {
                try {
                    return callback.doWithRetry();
                } catch (Throwable t) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException(t);
                }
            }, retryConfig);
            return result;
        } catch (Throwable t) {
            Throwable rootCause = (t instanceof RuntimeException && t.getCause() != null) ? t.getCause() : t;
            SnailRetryFailureTracker.record(rootCause, contextKey, scene);

            // 本地重试耗尽，尝试上报 SnailJob 服务端；仅上报成功时保留快照供远程/死信阶段消费
            boolean reported = false;
            try {
                reported = reportManualRetry(scene, callback.getClass().getName(), new Object[]{actualIdempotentId}, actualIdempotentId, bizNo);
            } catch (Throwable ex) {
                log.error("本地重试耗尽后上报 SnailJob 服务端异常 [Scene: {}, IdempotentId: {}]", scene, actualIdempotentId, ex);
            }
            keepContextForRemote = reported;

            if (rootCause instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "业务重试耗尽 (" + maxAttempts + " 次): " + (rootCause != null ? rootCause.getMessage() : "未知异常"),
                    rootCause);
        } finally {
            if (!keepContextForRemote) {
                this.contextStore.remove(contextKey);
                SnailRetryFailureTracker.remove(contextKey);
            }
            if (!StringUtils.hasText(previousTraceId)) {
                MDC.remove(TRACE_ID_KEY);
            }
        }
    }

    /**
     * 无返回值任务便捷重试执行
     *
     * @param scene 场景标识
     * @param task 无返回值任务
     */
    public void execute(String scene, Runnable task) {
        executeWithIdempotent(scene, null, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 带幂等键的无返回值任务便捷重试执行
     *
     * @param scene 场景标识
     * @param idempotentId 业务幂等标识
     * @param task 无返回值任务
     */
    public void executeWithIdempotent(String scene, String idempotentId, Runnable task) {
        executeWithIdempotent(scene, idempotentId, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 手动向 SnailJob 服务端上报重试失败任务
     *
     * @param scene 场景标识
     * @param targetClassName 执行器全限定类名
     * @param params 方法调用参数列表
     * @return 是否上报成功
     */
    public boolean reportManualRetry(String scene, String targetClassName, Object[] params) {
        return reportManualRetry(scene, targetClassName, params, null, null);
    }

    /**
     * 手动向 SnailJob 服务端上报重试失败任务（附带幂等与业务号）
     *
     * @param scene 场景标识
     * @param targetClassName 执行器全限定类名
     * @param params 方法调用参数列表
     * @param idempotentId 幂等键
     * @param bizNo 业务号
     * @return 是否上报成功
     */
    public boolean reportManualRetry(String scene, String targetClassName, Object[] params, String idempotentId, String bizNo) {
        if (!StringUtils.hasText(scene) || !StringUtils.hasText(targetClassName)) {
            log.warn("手动上报重试参数缺失: scene=[{}], targetClassName=[{}]", scene, targetClassName);
            return false;
        }

        // 若传入业务幂等标识，捕获并持久化当前四维上下文快照供远程重试消费
        if (StringUtils.hasText(idempotentId)) {
            String contextKey = scene + ":" + idempotentId;
            if (this.contextStore.get(contextKey) == null) {
                String previousTraceId = MDC.get(TRACE_ID_KEY);
                try {
                    ensureTraceId();
                    this.contextStore.put(contextKey, AsyncContext.ContextSnapshot.capture());
                } finally {
                    if (!StringUtils.hasText(previousTraceId)) {
                        MDC.remove(TRACE_ID_KEY);
                    }
                }
            }
        }

        Report report = resolveReport();
        if (report != null) {
            Integer previousStage = RetrySiteSnapshot.getStage();
            if (previousStage == null) {
                RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.MANUAL_REPORT.getStage());
            }
            try {
                return report.report(scene, targetClassName, params != null ? params : new Object[0]);
            } catch (Throwable t) {
                log.error("通过 SnailJob Report 门面上报异常 [Scene: {}, Target: {}]: {}", scene, targetClassName, t.getMessage(), t);
                return false;
            } finally {
                if (previousStage == null) {
                    RetrySiteSnapshot.removeStage();
                }
            }
        } else {
            log.debug("未装配 SnailJob Report 实例，进入离线自洽模拟上报 [Scene: {}, Target: {}]", scene, targetClassName);
            return true;
        }
    }

    private Report resolveReport() {
        if (this.preferredReport != null) {
            return this.preferredReport;
        }
        if (this.reportProvider == null) {
            return null;
        }
        try {
            Report unique = this.reportProvider.getIfUnique();
            if (unique != null) {
                return unique;
            }
            return this.reportProvider.orderedStream().findFirst().orElseGet(this.reportProvider::getIfAvailable);
        } catch (UnsupportedOperationException ex) {
            return this.reportProvider.getIfAvailable();
        }
    }

    private int resolveLocalMaxAttempts() {
        if (properties != null && properties.getRetry() != null && properties.getRetry().getLocalMaxAttempts() > 0) {
            return properties.getRetry().getLocalMaxAttempts();
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private Duration resolveLocalBackoff() {
        if (properties != null && properties.getRetry() != null && properties.getRetry().getLocalBackoff() != null
                && !properties.getRetry().getLocalBackoff().isNegative()) {
            return properties.getRetry().getLocalBackoff();
        }
        return Duration.ofMillis(DEFAULT_BACKOFF_MS);
    }

    private void ensureTraceId() {
        if (!StringUtils.hasText(MDC.get(TRACE_ID_KEY))) {
            MDC.put(TRACE_ID_KEY, IdUtil.fastSimpleUUID());
        }
    }

    /**
     * 重试业务执行回调函数式接口
     *
     * @param <T> 结果泛型
     */
    @FunctionalInterface
    public interface RetryCallback<T> {

        /**
         * 包含重试保护的业务逻辑执行体
         *
         * @return 业务返回结果
         * @throws Throwable 业务抛出的任何异常
         */
        T doWithRetry() throws Throwable;
    }
}
