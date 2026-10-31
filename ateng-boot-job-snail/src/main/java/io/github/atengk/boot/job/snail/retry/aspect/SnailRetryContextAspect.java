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
 *     <li><b>首次执行 / 本地拦截</b>：基于 {@link AsyncContext#capture()} 抓取当前 MDC traceId、User 操作人、
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

    public SnailRetryContextAspect() {
        this(null, null, null);
    }

    public SnailRetryContextAspect(
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator) {
        this.drainStateMachine = drainStateMachine;
        this.metricsBinder = metricsBinder;
        this.alertAggregator = alertAggregator;
    }

    @Around("@annotation(com.aizuda.snailjob.client.core.annotation.Retryable) || " +
            "@annotation(io.github.atengk.boot.job.snail.annotation.AtengSnailRetry)")
    public Object aroundRetry(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();
        Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

        AtengSnailRetry atengRetry = AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengSnailRetry.class);
        Retryable retryable = AnnotatedElementUtils.findMergedAnnotation(specificMethod, Retryable.class);

        String scene = (atengRetry != null && StringUtils.hasText(atengRetry.scene()))
                ? atengRetry.scene()
                : (retryable != null && StringUtils.hasText(retryable.scene()) ? retryable.scene() : specificMethod.getName());
        String executorClassName = targetClass.getName();
        String contextKey = scene + ":" + executorClassName + ":" + specificMethod.getName();
        String classKey = scene + ":" + executorClassName;
        String idempotentKey = resolveIdempotentKey(atengRetry, specificMethod, pjp.getArgs());
        String fineGrainedKey = StringUtils.hasText(idempotentKey) ? (contextKey + ":" + idempotentKey) : contextKey;

        // 0. 平滑停机排空拦截与在途计数
        if (drainStateMachine != null && drainStateMachine.isDraining()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "SnailJob 执行器正处于平滑停机排空阶段，拒绝接收新重试任务");
        }
        if (drainStateMachine != null) {
            drainStateMachine.incrementInFlightRetry();
        }
        long startTimeNanos = System.nanoTime();

        try {
            Object result;
            boolean isRemoteRetry = (RetrySiteSnapshot.getStage() != null
                    && RetrySiteSnapshot.getStage() == RetrySiteSnapshot.EnumStage.REMOTE.getStage())
                    || RetrySiteSnapshot.isRetryFlow();

            if (isRemoteRetry) {
                // 远程重试阶段：保存当前工作线程初始现场，优先通过细粒度幂等键还原上报时的四维快照
                AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
                AsyncContext.ContextSnapshot recorded = SnailRetryContextRegistry.get(fineGrainedKey);
                if (recorded == null && StringUtils.hasText(idempotentKey)) {
                    recorded = SnailRetryContextRegistry.get(scene + ":" + idempotentKey);
                }
                if (recorded == null) {
                    recorded = SnailRetryContextRegistry.get(contextKey);
                }
                if (recorded == null) {
                    recorded = SnailRetryContextRegistry.get(classKey);
                }

                if (recorded != null) {
                    recorded.apply();
                    log.debug("SnailJob 远程重试已成功还原四维上下文快照 [Key: {}]", fineGrainedKey);
                } else {
                    // 若内存快照未命中（如重启或多节点），从方法实参中深度二次提取
                    restoreFromArgsFallback(pjp.getArgs(), atengRetry);
                }

                try {
                    result = pjp.proceed();
                } finally {
                    previous.restore();
                    SnailRetryContextRegistry.remove(fineGrainedKey);
                    if (StringUtils.hasText(idempotentKey)) {
                        SnailRetryContextRegistry.remove(scene + ":" + idempotentKey);
                    }
                }
            } else {
                // 首次业务执行或本地重试阶段：捕获并登记当前四维快照（挂载细粒度键防多租户并发串写）
                ensureTraceIdPresent();
                AsyncContext.ContextSnapshot currentSnapshot = AsyncContext.ContextSnapshot.capture();
                SnailRetryContextRegistry.put(fineGrainedKey, currentSnapshot);
                if (StringUtils.hasText(idempotentKey)) {
                    SnailRetryContextRegistry.put(scene + ":" + idempotentKey, currentSnapshot);
                }
                SnailRetryContextRegistry.put(contextKey, currentSnapshot);
                SnailRetryContextRegistry.put(classKey, currentSnapshot);

                try {
                    result = pjp.proceed();
                } catch (Throwable t) {
                    // 发生异常即将上报，确保注册表中保持有效快照供远程重试消费
                    SnailRetryContextRegistry.put(fineGrainedKey, currentSnapshot);
                    if (StringUtils.hasText(idempotentKey)) {
                        SnailRetryContextRegistry.put(scene + ":" + idempotentKey, currentSnapshot);
                    }
                    SnailRetryContextRegistry.put(contextKey, currentSnapshot);
                    SnailRetryContextRegistry.put(classKey, currentSnapshot);
                    throw t;
                }
            }

            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordRetryExecution(scene, SnailJobMetricsBinder.STATUS_SUCCESS, TenantContextHolder.getTenantId(), Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onRetrySuccess(scene, null, MDC.get(TRACE_ID_KEY), durationMs);
            }
            return result;

        } catch (Throwable t) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordRetryExecution(scene, SnailJobMetricsBinder.STATUS_FAILURE, TenantContextHolder.getTenantId(), Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onRetryFailure(scene, null, MDC.get(TRACE_ID_KEY), durationMs, t);
            }
            throw t;
        } finally {
            if (drainStateMachine != null) {
                drainStateMachine.decrementInFlightRetry();
            }
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
            }
        }
    }

    /**
     * 根据业务注解与方法实参动态解析细粒度幂等标识（防御并发多租户快照串写）
     */
    private String resolveIdempotentKey(AtengSnailRetry atengRetry, Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return "empty";
        }
        if (atengRetry != null && StringUtils.hasText(atengRetry.idempotentSpel())) {
            try {
                return spelIdempotentIdGenerator.idGenerate(new IdempotentIdContext(
                        atengRetry.scene(),
                        method.getDeclaringClass().getName(),
                        args,
                        null,
                        method.getName()
                ));
            } catch (Throwable ignored) {
            }
        }
        try {
            return argsHashIdempotentIdGenerator.idGenerate(new IdempotentIdContext(
                    atengRetry != null ? atengRetry.scene() : "",
                    method.getDeclaringClass().getName(),
                    args,
                    null,
                    method.getName()
            ));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
