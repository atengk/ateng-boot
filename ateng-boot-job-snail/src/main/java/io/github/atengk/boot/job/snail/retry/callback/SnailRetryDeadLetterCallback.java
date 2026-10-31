package io.github.atengk.boot.job.snail.retry.callback;

import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.callback.complete.RetryCompleteCallback;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SnailJob 分布式重试死信沉降回调处理器
 *
 * <p>实现官方 {@link RetryCompleteCallback} 接口，当重试任务达到最大重试次数无法恢复时，
 * 自动拦截并路由至 {@link SnailJobAlertAggregator} 广播领域事件并推送协同高危告警卡片。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailRetryDeadLetterCallback implements RetryCompleteCallback {

    private static final Logger log = LoggerFactory.getLogger(SnailRetryDeadLetterCallback.class);
    public static final String TRACE_ID_KEY = "traceId";

    private final ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider;

    public SnailRetryDeadLetterCallback(ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider) {
        this.alertAggregatorProvider = alertAggregatorProvider;
    }

    @Override
    public void doSuccessCallback(String sceneName, String executorName, Object[] params) {
        log.debug("SnailJob 重试任务成功回调: scene={}, executor={}", sceneName, executorName);
        SnailJobAlertAggregator aggregator = alertAggregatorProvider != null ? alertAggregatorProvider.getIfAvailable() : null;
        if (aggregator != null) {
            aggregator.onRetrySuccess(sceneName);
        }
    }

    @Override
    public void doMaxRetryCallback(String sceneName, String executorName, Object[] params) {
        log.warn("SnailJob 重试任务达到最大次数触发死信回调: scene={}, executor={}", sceneName, executorName);

        String argsStr = null;
        if (params != null && params.length > 0) {
            try {
                argsStr = (params.length == 1 && params[0] instanceof String s) ? s : JsonUtil.toJsonString(params);
            } catch (Exception ignored) {
                argsStr = String.valueOf(params[0]);
            }
        }

        String traceId = MDC.get(TRACE_ID_KEY);
        int retryCount = 1;
        String bizNo = null;
        try {
            RetryerInfo retryerInfo = RetryerInfoCache.get(sceneName, executorName);
            if (retryerInfo != null) {
                if (retryerInfo.getLocalTimes() != null && retryerInfo.getLocalTimes() > 0) {
                    retryCount = retryerInfo.getLocalTimes();
                }
                bizNo = retryerInfo.getBizNo();
            }
        } catch (Throwable ignored) {
        }

        SnailJobAlertAggregator aggregator = alertAggregatorProvider != null ? alertAggregatorProvider.getIfAvailable() : null;
        if (aggregator != null) {
            aggregator.onRetryDeadLetter(sceneName, null, bizNo, executorName, argsStr, retryCount, null, traceId);
        }
    }
}
