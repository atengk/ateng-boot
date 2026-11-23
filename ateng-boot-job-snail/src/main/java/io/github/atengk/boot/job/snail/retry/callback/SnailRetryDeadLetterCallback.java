package io.github.atengk.boot.job.snail.retry.callback;

import com.aizuda.snailjob.client.core.IdempotentIdGenerate;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.callback.complete.RetryCompleteCallback;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryFailureTracker;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailJob 分布式重试死信沉降回调处理器
 *
 * <p>实现官方 {@link RetryCompleteCallback} 接口，当重试任务达到最大重试次数无法恢复时，
 * 自动从 {@link RetryerInfoCache}、重试现场上下文存储与失败因果跟踪器中提取完整链路元数据
 * （幂等键、{@code bizNo}、{@code traceId} 与真实 {@link Throwable} 因果），
 * 并路由至 {@link SnailJobAlertAggregator} 广播领域事件与推送告警卡片，同时清理瞬态索引键防止泄漏。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailRetryDeadLetterCallback implements RetryCompleteCallback {

    private static final Logger log = LoggerFactory.getLogger(SnailRetryDeadLetterCallback.class);
    public static final String TRACE_ID_KEY = "traceId";

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final Map<String, Expression> BIZ_NO_EXPRESSION_CACHE = new ConcurrentHashMap<>();

    private final ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider;
    private final ObjectProvider<SnailRetryContextStore> contextStoreProvider;
    private final ArgsHashIdempotentIdGenerator fallbackIdGenerator = new ArgsHashIdempotentIdGenerator();

    public SnailRetryDeadLetterCallback() {
        this(null, null);
    }

    public SnailRetryDeadLetterCallback(ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider) {
        this(alertAggregatorProvider, null);
    }

    public SnailRetryDeadLetterCallback(
            ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider,
            ObjectProvider<SnailRetryContextStore> contextStoreProvider) {
        this.alertAggregatorProvider = alertAggregatorProvider;
        this.contextStoreProvider = contextStoreProvider;
    }

    @Override
    public void doSuccessCallback(String sceneName, String executorName, Object[] params) {
        log.debug("SnailJob 重试任务成功回调: scene={}, executor={}", sceneName, executorName);
        RetryerInfo retryerInfo = getRetryerInfoQuietly(sceneName, executorName);
        SnailRetryContextStore contextStore = resolveContextStore();
        String idempotentId = resolveIdempotentId(sceneName, executorName, params, retryerInfo, contextStore);
        cleanupAssociatedState(sceneName, executorName, retryerInfo, idempotentId, contextStore);

        SnailJobAlertAggregator aggregator = alertAggregatorProvider != null ? alertAggregatorProvider.getIfAvailable() : null;
        if (aggregator != null) {
            aggregator.onRetrySuccess(sceneName);
        }
    }

    @Override
    public void doMaxRetryCallback(String sceneName, String executorName, Object[] params) {
        log.warn("SnailJob 重试任务达到最大次数触发死信回调: scene={}, executor={}", sceneName, executorName);

        String argsStr = formatArgs(params);
        RetryerInfo retryerInfo = getRetryerInfoQuietly(sceneName, executorName);
        SnailRetryContextStore contextStore = resolveContextStore();

        int retryCount = 1;
        if (retryerInfo != null && retryerInfo.getLocalTimes() != null && retryerInfo.getLocalTimes() > 0) {
            retryCount = retryerInfo.getLocalTimes();
        }

        String idempotentId = resolveIdempotentId(sceneName, executorName, params, retryerInfo, contextStore);
        String bizNo = resolveBizNo(params, retryerInfo);

        String methodName = (retryerInfo != null && retryerInfo.getMethod() != null) ? retryerInfo.getMethod().getName() : null;
        String contextKey = buildContextKey(sceneName, executorName, methodName);
        String classKey = buildClassKey(sceneName, executorName);
        String fineGrainedKey = buildFineGrainedKey(contextKey, idempotentId);
        String idemIndexKey = buildIdempotentIndexKey(sceneName, idempotentId);

        String traceId = resolveTraceId(params, contextStore, fineGrainedKey, idemIndexKey, contextKey, classKey);
        Throwable throwable = resolveFailureCause(params, fineGrainedKey, idemIndexKey, contextKey, classKey, sceneName);

        cleanupAssociatedState(sceneName, executorName, retryerInfo, idempotentId, contextStore);

        SnailJobAlertAggregator aggregator = alertAggregatorProvider != null ? alertAggregatorProvider.getIfAvailable() : null;
        if (aggregator != null) {
            aggregator.onRetryDeadLetter(sceneName, idempotentId, bizNo, executorName, argsStr, retryCount, throwable, traceId);
        }
    }

    private SnailRetryContextStore resolveContextStore() {
        SnailRetryContextStore store = contextStoreProvider != null ? contextStoreProvider.getIfAvailable() : null;
        return store != null ? store : SnailRetryContextRegistry.getStore();
    }

    private RetryerInfo getRetryerInfoQuietly(String sceneName, String executorName) {
        if (!StringUtils.hasText(sceneName) || !StringUtils.hasText(executorName)) {
            return null;
        }
        try {
            return RetryerInfoCache.get(sceneName, executorName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveIdempotentId(
            String sceneName,
            String executorName,
            Object[] params,
            RetryerInfo retryerInfo,
            SnailRetryContextStore contextStore) {
        if (retryerInfo != null && retryerInfo.getIdempotentIdGenerate() != null) {
            try {
                IdempotentIdGenerate generator = BeanUtils.instantiateClass(retryerInfo.getIdempotentIdGenerate());
                String methodName = retryerInfo.getMethod() != null ? retryerInfo.getMethod().getName() : null;
                String generated = generator.idGenerate(new IdempotentIdContext(
                        sceneName,
                        executorName,
                        params != null ? params : new Object[0],
                        null,
                        methodName
                ));
                if (StringUtils.hasText(generated)) {
                    return generated;
                }
            } catch (Throwable ex) {
                log.debug("死信回调生成幂等键降级 [Scene: {}, Executor: {}]: {}", sceneName, executorName, ex.getMessage());
            }
        }

        if (params != null && params.length == 1 && params[0] instanceof String singleParam && StringUtils.hasText(singleParam)) {
            if (contextStore != null && contextStore.get(buildIdempotentIndexKey(sceneName, singleParam)) != null) {
                return singleParam;
            }
        }

        String extracted = SnailJobContextAspect.extractPropertyFromArgs(
                params, "getIdempotentId", "getOrderNo", "getBizNo", "getOrderId", "getRequestId", "getId");
        if (StringUtils.hasText(extracted)) {
            return StringUtils.hasText(sceneName) ? (sceneName + "_" + extracted) : extracted;
        }

        if (params != null && params.length > 0 && StringUtils.hasText(sceneName)) {
            try {
                return fallbackIdGenerator.idGenerate(new IdempotentIdContext(
                        sceneName,
                        executorName,
                        params,
                        null,
                        retryerInfo != null && retryerInfo.getMethod() != null ? retryerInfo.getMethod().getName() : null
                ));
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String resolveBizNo(Object[] params, RetryerInfo retryerInfo) {
        if (retryerInfo != null && StringUtils.hasText(retryerInfo.getBizNo())) {
            String rawBizNo = retryerInfo.getBizNo().trim();
            if (rawBizNo.startsWith("#")) {
                String evaluated = evaluateBizNoSpel(rawBizNo, retryerInfo.getMethod(), params);
                if (StringUtils.hasText(evaluated)) {
                    return evaluated;
                }
            } else {
                return rawBizNo;
            }
        }
        return SnailJobContextAspect.extractPropertyFromArgs(params, "getBizNo", "getOrderNo", "getOrderId", "getId");
    }

    private String evaluateBizNoSpel(String spel, Method method, Object[] params) {
        if (params == null || params.length == 0) {
            return null;
        }
        try {
            Expression expression = BIZ_NO_EXPRESSION_CACHE.computeIfAbsent(spel, SPEL_PARSER::parseExpression);
            StandardEvaluationContext evalContext = new StandardEvaluationContext();
            String[] paramNames = method != null ? PARAM_NAME_DISCOVERER.getParameterNames(method) : null;
            for (int i = 0; i < params.length; i++) {
                Object arg = params[i];
                evalContext.setVariable("p" + i, arg);
                evalContext.setVariable("a" + i, arg);
                if (paramNames != null && i < paramNames.length) {
                    evalContext.setVariable(paramNames[i], arg);
                }
            }
            if (params.length == 1 && params[0] != null) {
                evalContext.setRootObject(params[0]);
            }
            Object value = expression.getValue(evalContext);
            return value != null ? String.valueOf(value).trim() : null;
        } catch (Throwable ex) {
            log.debug("死信回调解析 bizNo SpEL 失败 [{}]: {}", spel, ex.getMessage());
            return null;
        }
    }

    private String resolveTraceId(
            Object[] params,
            SnailRetryContextStore contextStore,
            String fineGrainedKey,
            String idemIndexKey,
            String contextKey,
            String classKey) {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }

        if (contextStore != null) {
            AsyncContext.ContextSnapshot snapshot = findFirstSnapshot(
                    contextStore, fineGrainedKey, idemIndexKey, contextKey, classKey);
            if (snapshot != null) {
                AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
                try {
                    snapshot.apply();
                    String snapshotTraceId = MDC.get(TRACE_ID_KEY);
                    if (StringUtils.hasText(snapshotTraceId)) {
                        return snapshotTraceId;
                    }
                } finally {
                    previous.restore();
                }
            }
        }

        return SnailJobContextAspect.extractPropertyFromArgs(params, "getTraceId");
    }

    private AsyncContext.ContextSnapshot findFirstSnapshot(SnailRetryContextStore contextStore, String... keys) {
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            AsyncContext.ContextSnapshot snapshot = contextStore.get(key);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    private Throwable resolveFailureCause(Object[] params, String... candidateKeys) {
        if (params != null) {
            for (Object param : params) {
                if (param instanceof Throwable t) {
                    return t;
                }
            }
        }
        return SnailRetryFailureTracker.find(candidateKeys);
    }

    private void cleanupAssociatedState(
            String sceneName,
            String executorName,
            RetryerInfo retryerInfo,
            String idempotentId,
            SnailRetryContextStore contextStore) {
        String methodName = (retryerInfo != null && retryerInfo.getMethod() != null) ? retryerInfo.getMethod().getName() : null;
        String contextKey = buildContextKey(sceneName, executorName, methodName);
        String classKey = buildClassKey(sceneName, executorName);
        String fineGrainedKey = buildFineGrainedKey(contextKey, idempotentId);
        String idemIndexKey = buildIdempotentIndexKey(sceneName, idempotentId);

        if (contextStore != null) {
            removeKeys(contextStore, fineGrainedKey, idemIndexKey, contextKey, classKey);
        }
        SnailRetryFailureTracker.remove(fineGrainedKey, idemIndexKey, contextKey, classKey, sceneName);
    }

    private void removeKeys(SnailRetryContextStore contextStore, String... keys) {
        for (String key : keys) {
            if (StringUtils.hasText(key)) {
                contextStore.remove(key);
            }
        }
    }

    private String formatArgs(Object[] params) {
        if (params == null || params.length == 0) {
            return null;
        }
        try {
            return (params.length == 1 && params[0] instanceof String s) ? s : JsonUtil.toJsonString(params);
        } catch (Exception ignored) {
            return String.valueOf(params[0]);
        }
    }

    private static String buildContextKey(String sceneName, String executorName, String methodName) {
        if (!StringUtils.hasText(sceneName) || !StringUtils.hasText(executorName) || !StringUtils.hasText(methodName)) {
            return null;
        }
        return sceneName + ":" + executorName + ":" + methodName;
    }

    private static String buildClassKey(String sceneName, String executorName) {
        if (!StringUtils.hasText(sceneName) || !StringUtils.hasText(executorName)) {
            return null;
        }
        return sceneName + ":" + executorName;
    }

    private static String buildFineGrainedKey(String contextKey, String idempotentId) {
        if (!StringUtils.hasText(contextKey)) {
            return null;
        }
        return StringUtils.hasText(idempotentId) ? (contextKey + ":" + idempotentId) : contextKey;
    }

    private static String buildIdempotentIndexKey(String sceneName, String idempotentId) {
        if (!StringUtils.hasText(sceneName) || !StringUtils.hasText(idempotentId)) {
            return null;
        }
        return sceneName + ":" + idempotentId;
    }
}
