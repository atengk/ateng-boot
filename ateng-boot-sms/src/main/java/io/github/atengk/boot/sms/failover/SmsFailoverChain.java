package io.github.atengk.boot.sms.failover;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 短信高可用顺延容灾降级链执行器
 * 当主渠道调用遭遇云厂商网络超时、接口限流或 5xx 故障时，自动顺延切换至备用渠道重试并输出降级审计日志
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsFailoverChain {

    private final SmsChannelRegistry channelRegistry;
    private final Map<SmsSupplier, SmsProvider> providerMap;
    private final List<SmsSendFailureHandler> failureHandlers;
    @Getter
    private final SmsChannelCircuitBreaker circuitBreaker;

    public SmsFailoverChain(SmsChannelRegistry channelRegistry,
                            Map<SmsSupplier, SmsProvider> providerMap,
                            List<SmsSendFailureHandler> failureHandlers,
                            SmsChannelCircuitBreaker circuitBreaker) {
        this.channelRegistry = channelRegistry;
        this.providerMap = providerMap != null ? providerMap : Collections.emptyMap();
        this.failureHandlers = failureHandlers != null ? failureHandlers : Collections.emptyList();
        this.circuitBreaker = circuitBreaker;
    }

    public SmsFailoverChain(SmsChannelRegistry channelRegistry,
                            Map<SmsSupplier, SmsProvider> providerMap,
                            List<SmsSendFailureHandler> failureHandlers) {
        this(channelRegistry, providerMap, failureHandlers, null);
    }

    public SmsFailoverChain(SmsChannelRegistry channelRegistry, Map<SmsSupplier, SmsProvider> providerMap) {
        this(channelRegistry, providerMap, null, null);
    }

    /**
     * 在高可用顺延容灾链中执行短信发送
     *
     * @param request        发信请求对象
     * @param initialChannel 初始主发信渠道
     * @return SmsResult 发信成功结果
     * @throws SmsSendException 当主渠道及其所有备用容灾渠道均投递失败时抛出
     */
    public SmsResult execute(SmsRequest request, SmsChannel initialChannel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(initialChannel, "初始渠道 SmsChannel 不能为空");

        Set<String> visitedChannels = new HashSet<>();
        SmsChannel currentChannel = initialChannel;
        Exception lastException = null;

        SmsRequest lastAttemptRequest = request;

        while (currentChannel != null) {
            final SmsChannel attemptChannel = currentChannel;
            visitedChannels.add(attemptChannel.getName());

            // 检查健康度熔断：若渠道处于熔断开路态 (OPEN) 且配置了备用容灾渠道，短路毫秒级跳过网络调用
            if (circuitBreaker != null && !circuitBreaker.allowRequest(attemptChannel.getName())) {
                String failoverChannelName = attemptChannel.getFailoverChannel();
                if (failoverChannelName != null && !failoverChannelName.isBlank()) {
                    String nextName = failoverChannelName.trim();
                    if (!visitedChannels.contains(nextName)) {
                        SmsChannel backupChannel = channelRegistry != null ? channelRegistry.get(nextName) : null;
                        if (backupChannel != null) {
                            log.warn("[SmsFailoverChain] 渠道 [{}] 处于短时熔断开路态 (OPEN)，短路毫秒级跳过 -> 自动切换至备用渠道: [{}] (目标号码: {})",
                                    attemptChannel.getName(),
                                    backupChannel.getName(),
                                    DesensitizeUtil.mobilePhone(request.getPhone()));
                            lastException = new SmsSendException(attemptChannel.getName(), "CIRCUIT_BREAKER_OPEN",
                                    "渠道处于短时熔断开路态: " + attemptChannel.getName(),
                                    SmsResultCode.SMS_SEND_ERROR,
                                    "渠道处于短时熔断开路态: " + attemptChannel.getName());
                            currentChannel = backupChannel;
                            continue;
                        }
                    }
                }
                log.warn("[SmsFailoverChain] 渠道 [{}] 处于短时熔断开路态 (OPEN) 且无可用备用渠道，终止顺延", attemptChannel.getName());
                lastException = new SmsSendException(attemptChannel.getName(), "CIRCUIT_BREAKER_OPEN",
                        "渠道处于短时熔断开路态: " + attemptChannel.getName(),
                        SmsResultCode.SMS_SEND_ERROR,
                        "渠道处于短时熔断开路态: " + attemptChannel.getName());
                break;
            }

            // 针对当前尝试渠道执行逻辑模板 ID 与专属签名转译
            String targetTemplateId = attemptChannel.resolveTemplateId(request.getTemplateId());
            BusinessAssert.hasText(targetTemplateId,
                    () -> "短信模板编码不能为空 (渠道 [" + attemptChannel.getName() + "] 未配置 defaultTemplateId 且发信请求未指定模板)");
            String targetSignName = attemptChannel.resolveSignName(request.getTemplateId(), request.getSignName());
            SmsRequest channelRequest = request.withTemplateSignAndChannel(targetTemplateId, targetSignName, attemptChannel.getName());
            lastAttemptRequest = channelRequest;

            SmsProvider provider = providerMap.get(attemptChannel.getSupplier());
            if (provider == null) {
                lastException = new SmsSendException(attemptChannel.getName(), "PROVIDER_NOT_FOUND",
                        "未找到云厂商驱动: " + attemptChannel.getSupplier(), SmsResultCode.SMS_CHANNEL_NOT_FOUND,
                        "未找到云厂商驱动: " + attemptChannel.getSupplier());
            } else {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("[SmsFailoverChain] 尝试通过渠道 [{}] 投递短信 -> 厂商: {}, 模板: {}, 签名: {}, 目标号码: {}",
                                attemptChannel.getName(),
                                attemptChannel.getSupplier(),
                                targetTemplateId,
                                targetSignName,
                                DesensitizeUtil.mobilePhone(request.getPhone()));
                    }

                    SmsResult result = provider.send(channelRequest, attemptChannel);
                    if (result != null && result.isSuccess()) {
                        if (circuitBreaker != null) {
                            circuitBreaker.recordSuccess(attemptChannel.getName());
                        }
                        if (!attemptChannel.getName().equals(initialChannel.getName())) {
                            log.warn("[SmsFailoverChain] 顺延容灾降级投递成功 -> 主渠道: [{}], 降级渠道: [{}], 厂商: {}, 目标号码: {}",
                                    initialChannel.getName(),
                                    attemptChannel.getName(),
                                    attemptChannel.getSupplier(),
                                    DesensitizeUtil.mobilePhone(request.getPhone()));
                        }
                        return result;
                    }

                    // 厂商未抛异常但显式返回失败
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(attemptChannel.getName());
                    }
                    String rawCode = result != null ? result.getRawCode() : "UNKNOWN";
                    String rawMsg = result != null ? result.getRawMessage() : "Send failed";
                    lastException = new SmsSendException(attemptChannel.getName(), rawCode, rawMsg,
                            SmsResultCode.SMS_SEND_ERROR, rawMsg);
                } catch (Exception ex) {
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(attemptChannel.getName());
                    }
                    lastException = ex;
                }
            }

            // 检查当前渠道是否配置了备用容灾渠道
            String failoverChannelName = currentChannel.getFailoverChannel();
            if (failoverChannelName == null || failoverChannelName.isBlank()) {
                // 未配置容灾备用渠道，终止降级链
                break;
            }

            String nextName = failoverChannelName.trim();

            // 环路依赖防卫 (A -> B -> A)
            if (visitedChannels.contains(nextName)) {
                log.error("[SmsFailoverChain] 检测到渠道容灾配置环路引用 [{}] -> [{}]，终止顺延降级",
                        currentChannel.getName(), nextName);
                break;
            }

            SmsChannel backupChannel = channelRegistry != null ? channelRegistry.get(nextName) : null;
            if (backupChannel == null) {
                log.error("[SmsFailoverChain] 渠道 [{}] 配置的容灾备用渠道 [{}] 不存在，终止顺延降级",
                        currentChannel.getName(), nextName);
                break;
            }

            log.warn("[SmsFailoverChain] 渠道 [{}] 发生发信异常 ({})，自动触发高可用顺延容灾降级 -> 切换至备用渠道: [{}] (目标号码: {})",
                    currentChannel.getName(),
                    lastException.getMessage(),
                    backupChannel.getName(),
                    DesensitizeUtil.mobilePhone(request.getPhone()));

            currentChannel = backupChannel;
        }

        // 容灾链全部尝试失败，触发最终失败补偿处理器
        if (failureHandlers != null && !failureHandlers.isEmpty()) {
            for (SmsSendFailureHandler handler : failureHandlers) {
                if (handler != null) {
                    try {
                        handler.handle(lastAttemptRequest != null ? lastAttemptRequest : request, currentChannel != null ? currentChannel : initialChannel, lastException);
                    } catch (Exception handlerEx) {
                        log.error("[SmsFailoverChain] 失败补偿处理器 [{}] 执行异常: {}", handler.getClass().getName(), handlerEx.getMessage());
                    }
                }
            }
        }

        if (lastException instanceof SmsSendException sse) {
            throw sse;
        }
        throw new SmsSendException(initialChannel.getName(), "FAILOVER_EXHAUSTED",
                "渠道及其容灾备用链路全部投递失败: " + (lastException != null ? lastException.getMessage() : "Unknown"),
                SmsResultCode.SMS_SEND_ERROR,
                lastException != null ? lastException.getMessage() : "Failover exhausted",
                lastException);
    }
}
