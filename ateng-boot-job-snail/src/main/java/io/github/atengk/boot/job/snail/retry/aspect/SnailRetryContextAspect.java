package io.github.atengk.boot.job.snail.retry.aspect;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.intercepter.RetrySiteSnapshot;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryFailureTracker;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * SnailJob 分布式重试四维现场快照与跨时间/跨节点保真还原切面
 *
 * <p>拦截标注有 {@link Retryable} 或 {@link AtengSnailRetry} 的业务方法：
 * <ul>
 *     <li><b>首次执行 / 本地拦截</b>：基于 {@link AsyncContext.ContextSnapshot#capture()} 抓取当前 MDC traceId、User 操作人、
 *     Tenant 租户与 IgnoreTenant 标志，登记至 {@link SnailRetryContextRegistry}；</li>
 *     <li><b>远程重试 / 跨节点执行</b>：当流量作为远程重试分发到达工作线程时，自动解包并还原四维现场，
 *     彻底杜绝跨租户越权查询与脏读；并在 {@code finally} 严格还原现场，确保线程池复用安全。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 90)
public class SnailRetryContextAspect {

    private static final Logger log = LoggerFactory.getLogger(SnailRetryContextAspect.class);

    public static final String TRACE_ID_KEY = "traceId";
    public static final String DEFAULT_USER = "SYSTEM";

    private final GracefulDrainStateMachine drainStateMachine;
    private final SnailJobMetricsBinder metricsBinder;
    private final SnailJobAlertAggregator alertAggregator;

    private final SpelIdempotentIdGenerator spelIdempotentIdGenerator = new SpelIdempotentIdGenerator();
    private final ArgsHashIdempotentIdGenerator argsHashIdempotentIdGenerator = new ArgsHashIdempotentIdGenerator();

    private final SnailRetryContextStore contextStore;

    public SnailRetryContextAspect() {
        this(null, null, null, null);
    }

    public SnailRetryContextAspect(
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator) {
        this(drainStateMachine, metricsBinder, alertAggregator, null);
    }

    public SnailRetryContextAspect(
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator,
            SnailRetryContextStore contextStore) {
        this.drainStateMachine = drainStateMachine;
        this.metricsBinder = metricsBinder;
        this.alertAggregator = alertAggregator;
        this.contextStore = (contextStore != null) ? contextStore : SnailRetryContextRegistry.getStore();
    }

    public SnailRetryContextStore getContextStore() {
        return contextStore;
    }

    @Around("@annotation(com.aizuda.snailjob.client.core.annotation.Retryable) || " +
            "@annotation(io.github.atengk.boot.job.snail.annotation.AtengSnailRetry)")
    public Object aroundRetry(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();
        Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

        AtengSnailRetry atengRetry = AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengSnailRetry.class);
        if (atengRetry == null) {
            atengRetry = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailRetry.class);
        }
        Retryable directRetryable = specificMethod.getAnnotation(Retryable.class);
        Retryable mergedRetryable = AnnotatedElementUtils.findMergedAnnotation(specificMethod, Retryable.class);

        String scene = resolveScene(specificMethod, atengRetry, directRetryable, mergedRetryable);
        String executorClassName = targetClass.getName();
        String contextKey = scene + ":" + executorClassName + ":" + specificMethod.getName();
        String classKey = scene + ":" + executorClassName;
        String idempotentKey = resolveIdempotentKey(scene, atengRetry, specificMethod, pjp.getArgs());
        String fineGrainedKey = StringUtils.hasText(idempotentKey) ? (contextKey + ":" + idempotentKey) : contextKey;

        // 0. 平滑停机排空拦截与在途计数
        if (drainStateMachine != null && drainStateMachine.isDraining()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "SnailJob 执行器正处于平滑停机排空阶段，拒绝接收新重试任务");
        }
        if (drainStateMachine != null) {
            drainStateMachine.incrementInFlightRetry();
        }
        long startTimeNanos = System.nanoTime();
        String observedTraceId = MDC.get(TRACE_ID_KEY);
        String observedTenantId = TenantContextHolder.getTenantId();

        try {
            Object result;
            Integer stage = RetrySiteSnapshot.getStage();
            boolean isRemoteRetry = (stage != null && stage == RetrySiteSnapshot.EnumStage.REMOTE.getStage())
                    || (stage == null && RetrySiteSnapshot.isRetryFlow());
            boolean isLocalRetryFlow = !isRemoteRetry
                    && stage != null
                    && stage == RetrySiteSnapshot.EnumStage.LOCAL.getStage()
                    && RetrySiteSnapshot.isRetryFlow();

            if (isRemoteRetry || isLocalRetryFlow) {
                // 远程重试或本地重试流：暂存当前线程初始现场，优先还原上报/首次执行时保存的四维快照
                AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
                AsyncContext.ContextSnapshot recorded = findRecordedSnapshot(fineGrainedKey, scene, idempotentKey, contextKey, classKey);
                if (recorded != null) {
                    recorded.apply();
                    log.debug("SnailJob 重试流已成功还原四维上下文快照 [Stage: {}, Key: {}]",
                            isRemoteRetry ? "REMOTE" : "LOCAL", fineGrainedKey);
                } else {
                    ensureTraceIdPresent();
                    if (isRemoteRetry) {
                        restoreFromArgsFallback(pjp.getArgs(), atengRetry);
                    } else {
                        applyTenantModeOnFirstExecution(atengRetry, pjp.getArgs());
                    }
                }
                observedTraceId = MDC.get(TRACE_ID_KEY);
                observedTenantId = TenantContextHolder.getTenantId();

                boolean succeeded = false;
                try {
                    result = pjp.proceed();
                    succeeded = true;
                } finally {
                    previous.restore();
                    if (isRemoteRetry) {
                        // 远程执行结束必须清理粗粒度共享索引键防跨租户串扰；成功完结时同步清理细粒度键
                        this.contextStore.remove(contextKey);
                        this.contextStore.remove(classKey);
                        SnailRetryFailureTracker.remove(contextKey, classKey);
                        if (succeeded) {
                            removeFineGrainedKeys(fineGrainedKey, scene, idempotentKey);
                        }
                    } else if (succeeded) {
                        // 本地重试成功自愈后再统一清理全量索引键，失败期间保留快照供下一次本地重试或远程上报消费
                        cleanupAllKeys(fineGrainedKey, scene, idempotentKey, contextKey, classKey);
                    }
                }
            } else {
                // 首次业务执行：即时生效 @AtengSnailRetry#tenantMode() 并捕获登记四维快照，退出时恢复调用方线程现场
                AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
                try {
                    ensureTraceIdPresent();
                    applyTenantModeOnFirstExecution(atengRetry, pjp.getArgs());
                    observedTraceId = MDC.get(TRACE_ID_KEY);
                    observedTenantId = TenantContextHolder.getTenantId();
                    AsyncContext.ContextSnapshot currentSnapshot = AsyncContext.ContextSnapshot.capture();
                    storeSnapshot(fineGrainedKey, scene, idempotentKey, contextKey, classKey, currentSnapshot);
                    result = pjp.proceed();
                } finally {
                    previous.restore();
                }
            }

            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordRetryExecution(scene, SnailJobMetricsBinder.STATUS_SUCCESS, observedTenantId, Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onRetrySuccess(scene, null, observedTraceId, durationMs);
            }
            return result;

        } catch (Throwable t) {
            SnailRetryFailureTracker.record(
                    t,
                    fineGrainedKey,
                    StringUtils.hasText(idempotentKey) ? (scene + ":" + idempotentKey) : null,
                    contextKey,
                    classKey,
                    scene
            );
            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordRetryExecution(scene, SnailJobMetricsBinder.STATUS_FAILURE, observedTenantId, Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onRetryFailure(scene, null, observedTraceId, durationMs, t);
            }
            throw t;
        } finally {
            if (drainStateMachine != null) {
                drainStateMachine.decrementInFlightRetry();
            }
        }
    }

    private static String resolveScene(
            Method specificMethod,
            AtengSnailRetry atengRetry,
            Retryable directRetryable,
            Retryable mergedRetryable) {
        if (directRetryable != null && StringUtils.hasText(directRetryable.scene())) {
            return directRetryable.scene().trim();
        }
        if (atengRetry != null) {
            if (StringUtils.hasText(atengRetry.scene())) {
                return atengRetry.scene().trim();
            }
            if (StringUtils.hasText(atengRetry.value())) {
                return atengRetry.value().trim();
            }
        }
        if (mergedRetryable != null && StringUtils.hasText(mergedRetryable.scene())) {
            return mergedRetryable.scene().trim();
        }
        return specificMethod.getName();
    }

    private AsyncContext.ContextSnapshot findRecordedSnapshot(
            String fineGrainedKey,
            String scene,
            String idempotentKey,
            String contextKey,
            String classKey) {
        AsyncContext.ContextSnapshot recorded = this.contextStore.get(fineGrainedKey);
        if (recorded == null && StringUtils.hasText(idempotentKey)) {
            recorded = this.contextStore.get(scene + ":" + idempotentKey);
        }
        if (recorded == null) {
            recorded = this.contextStore.get(contextKey);
        }
        if (recorded == null) {
            recorded = this.contextStore.get(classKey);
        }
        return recorded;
    }

    private void storeSnapshot(
            String fineGrainedKey,
            String scene,
            String idempotentKey,
            String contextKey,
            String classKey,
            AsyncContext.ContextSnapshot snapshot) {
        this.contextStore.put(fineGrainedKey, snapshot);
        if (StringUtils.hasText(idempotentKey)) {
            this.contextStore.put(scene + ":" + idempotentKey, snapshot);
        }
        this.contextStore.put(contextKey, snapshot);
        this.contextStore.put(classKey, snapshot);
    }

    private void removeFineGrainedKeys(String fineGrainedKey, String scene, String idempotentKey) {
        this.contextStore.remove(fineGrainedKey);
        SnailRetryFailureTracker.remove(fineGrainedKey);
        if (StringUtils.hasText(idempotentKey)) {
            String idemIndexKey = scene + ":" + idempotentKey;
            this.contextStore.remove(idemIndexKey);
            SnailRetryFailureTracker.remove(idemIndexKey);
        }
    }

    private void cleanupAllKeys(
            String fineGrainedKey,
            String scene,
            String idempotentKey,
            String contextKey,
            String classKey) {
        removeFineGrainedKeys(fineGrainedKey, scene, idempotentKey);
        this.contextStore.remove(contextKey);
        this.contextStore.remove(classKey);
        SnailRetryFailureTracker.remove(contextKey, classKey);
    }

    private void applyTenantModeOnFirstExecution(AtengSnailRetry atengRetry, Object[] args) {
        if (atengRetry == null) {
            return;
        }
        JobTenantMode tenantMode = atengRetry.tenantMode();
        if (tenantMode == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (tenantMode == JobTenantMode.PARAM) {
            if (!StringUtils.hasText(TenantContextHolder.getTenantId())) {
                String tenantId = SnailJobContextAspect.extractPropertyFromArgs(args, "getTenantId");
                if (StringUtils.hasText(tenantId)) {
                    TenantContextHolder.setTenantId(tenantId);
                }
            }
        } else if (tenantMode == JobTenantMode.ITERATE) {
            TenantContextHolder.clear();
        }
    }

    private void ensureTraceIdPresent() {
        if (!StringUtils.hasText(MDC.get(TRACE_ID_KEY))) {
            MDC.put(TRACE_ID_KEY, IdUtil.fastSimpleUUID());
        }
    }

    /**
     * 当跨机或重启导致本地快照未命中时，从入参对象中反射提取并回填上下文
     */
    private void restoreFromArgsFallback(Object[] args, AtengSnailRetry atengRetry) {
        if (args == null || args.length == 0) {
            return;
        }

        String traceId = SnailJobContextAspect.extractPropertyFromArgs(args, "getTraceId");
        if (StringUtils.hasText(traceId)) {
            MDC.put(TRACE_ID_KEY, traceId);
        } else if (!StringUtils.hasText(MDC.get(TRACE_ID_KEY))) {
            MDC.put(TRACE_ID_KEY, IdUtil.fastSimpleUUID());
        }

        String user = SnailJobContextAspect.extractPropertyFromArgs(args, "getUserId", "getUser");
        if (StringUtils.hasText(user)) {
            UserContextHolder.set(user);
        } else if (!StringUtils.hasText(UserContextHolder.getExplicit())) {
            UserContextHolder.set(DEFAULT_USER);
        }

        JobTenantMode tenantMode = atengRetry != null ? atengRetry.tenantMode() : JobTenantMode.PARAM;
        if (tenantMode == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (tenantMode == JobTenantMode.PARAM) {
            String tenantId = SnailJobContextAspect.extractPropertyFromArgs(args, "getTenantId");
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            } else {
                log.warn("[SnailRetry] 远程重试从入参反射提取租户标识失败，入参缺少 getTenantId 访问器或为 null，当前上下文未绑定租户");
            }
        }
    }

    /**
     * 根据业务注解与方法实参动态解析细粒度幂等标识（防御并发多租户快照串写）
     */
    private String resolveIdempotentKey(String scene, AtengSnailRetry atengRetry, Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return "empty";
        }
        if (atengRetry != null && StringUtils.hasText(atengRetry.idempotentSpel())) {
            try {
                return spelIdempotentIdGenerator.idGenerate(new IdempotentIdContext(
                        scene,
                        method.getDeclaringClass().getName(),
                        args,
                        null,
                        method.getName()
                ));
            } catch (Throwable ex) {
                log.trace("[SnailRetryContextAspect] SpEL 幂等键生成失败，降级使用参数哈希生成器: {}", ex.getMessage());
            }
        }
        try {
            return argsHashIdempotentIdGenerator.idGenerate(new IdempotentIdContext(
                    scene,
                    method.getDeclaringClass().getName(),
                    args,
                    null,
                    method.getName()
            ));
        } catch (Throwable ex) {
            log.trace("[SnailRetryContextAspect] 参数哈希幂等键生成失败，跳过细粒度键: {}", ex.getMessage());
            return null;
        }
    }
}
