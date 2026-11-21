package io.github.atengk.boot.sms.failover;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.exception.SmsException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.guard.SmsQuotaGuard;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信高可用顺延容灾降级链执行器
 * 当主渠道调用遭遇云厂商网络超时、接口限流或 5xx 故障时，自动顺延切换至备用渠道重试并输出降级审计日志
 * 底层基于规范化 {@code providerCode} 路由索引（兼顾兼容 {@link SmsSupplier} 枚举），支持无限多自定义驱动与纯文本直发检验
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsFailoverChain {

    private final SmsChannelRegistry channelRegistry;
    private final Map<String, SmsProvider> providerCodeMap = new ConcurrentHashMap<>();
    private final List<SmsSendFailureHandler> failureHandlers;
    @Getter
    private final SmsChannelCircuitBreaker circuitBreaker;
    @Getter
    private final SmsQuotaGuard quotaGuard;

    public SmsFailoverChain(SmsChannelRegistry channelRegistry,
                            List<SmsProvider> providers,
                            List<SmsSendFailureHandler> failureHandlers,
                            SmsChannelCircuitBreaker circuitBreaker,
                            SmsQuotaGuard quotaGuard) {
        this.channelRegistry = channelRegistry;
        this.failureHandlers = failureHandlers != null ? failureHandlers : Collections.emptyList();
        this.circuitBreaker = circuitBreaker;
        this.quotaGuard = quotaGuard;
        if (providers != null) {
            for (SmsProvider provider : providers) {
                String code = resolveProviderCode(provider);
                if (code != null) {
                    this.providerCodeMap.put(code, provider);
                }
            }
        }
    }

    public SmsFailoverChain(SmsChannelRegistry channelRegistry,
                            List<SmsProvider> providers,
                            List<SmsSendFailureHandler> failureHandlers,
                            SmsChannelCircuitBreaker circuitBreaker) {
        this(channelRegistry, providers, failureHandlers, circuitBreaker, null);
    }

    public SmsFailoverChain(SmsChannelRegistry channelRegistry,
                            Map<SmsSupplier, SmsProvider> providerMap,
                            List<SmsSendFailureHandler> failureHandlers,
                            SmsChannelCircuitBreaker circuitBreaker) {
        this.channelRegistry = channelRegistry;
        this.failureHandlers = failureHandlers != null ? failureHandlers : Collections.emptyList();
        this.circuitBreaker = circuitBreaker;
        this.quotaGuard = null;
        if (providerMap != null) {
            providerMap.forEach((supplier, provider) -> {
                if (provider != null) {
                    String code = resolveProviderCode(provider);
                    if (code != null) {
                        this.providerCodeMap.put(code, provider);
                    }
                    if (supplier != null) {
                        this.providerCodeMap.putIfAbsent(supplier.getCode().toLowerCase(Locale.ROOT), provider);
                    }
                }
            });
        }
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
     * 解析短信驱动的规范化小写路由编码
     *
     * @param provider 短信驱动实例
     * @return 小写驱动路由编码，无法推导时返回 null
     */
    public static String resolveProviderCode(SmsProvider provider) {
        if (provider == null) {
            return null;
        }
        String code = provider.getProviderCode();
        if (StringUtils.hasText(code)) {
            return code.trim().toLowerCase(Locale.ROOT);
        }
        return provider.getSupplier() != null ? provider.getSupplier().getCode().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 按渠道配置（优先 {@code providerCode}，缺省回退 {@link SmsSupplier}）解析对应驱动实现
     *
     * @param channel 目标短信渠道
     * @return 匹配的 SmsProvider，未找到时返回 null
     */
    public SmsProvider resolveProvider(SmsChannel channel) {
        if (channel == null) {
            return null;
        }
        String code = channel.resolveProviderCode();
        return code != null ? providerCodeMap.get(code) : null;
    }

    /**
     * 按规范化驱动编码检索已注册驱动
     *
     * @param providerCode 驱动编码（不区分大小写）
     * @return SmsProvider，不存在时返回 null
     */
    public SmsProvider getProvider(String providerCode) {
        if (!StringUtils.hasText(providerCode)) {
            return null;
        }
        return providerCodeMap.get(providerCode.trim().toLowerCase(Locale.ROOT));
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
        boolean isPlainText = !StringUtils.hasText(request.getTemplateId()) && StringUtils.hasText(request.getContent());

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
            String targetTemplateId = isPlainText ? null : attemptChannel.resolveTemplateId(request.getTemplateId());
            if (!isPlainText) {
                BusinessAssert.hasText(targetTemplateId,
                        () -> "短信模板编码不能为空 (渠道 [" + attemptChannel.getName() + "] 未配置 defaultTemplateId 且发信请求未指定模板)");
            }
            String targetSignName = attemptChannel.resolveSignName(request.getTemplateId(), request.getSignName());
            SmsRequest channelRequest = request.withTemplateSignAndChannel(targetTemplateId, targetSignName, attemptChannel.getName());
            lastAttemptRequest = channelRequest;

            SmsProvider provider = resolveProvider(attemptChannel);
            if (provider == null) {
                String missingCode = attemptChannel.resolveProviderCode();
                lastException = new SmsSendException(attemptChannel.getName(), "PROVIDER_NOT_FOUND",
                        "未找到云厂商驱动: " + missingCode, SmsResultCode.SMS_CHANNEL_NOT_FOUND,
                        "未找到云厂商驱动: " + missingCode);
            } else if (isPlainText && !provider.supportsPlainText()) {
                SmsException unsupportedEx = new SmsException(SmsResultCode.SMS_PARAM_INVALID,
                        "短信厂商 [" + attemptChannel.resolveProviderCode() + "] 仅支持模板发送，不支持无模板纯文本直发");
                if (visitedChannels.size() == 1) {
                    throw unsupportedEx;
                }
                lastException = unsupportedEx;
            } else if (quotaGuard != null && !quotaGuard.tryAcquire(attemptChannel, request.getPhones().size())) {
                long used = quotaGuard.getUsedQuota(attemptChannel);
                log.warn("[SmsFailoverChain] 渠道 [{}] 已达配额上限 (已用: {}, 上限: {})，跳过并尝试顺延备用渠道",
                        attemptChannel.getName(), used, attemptChannel.getMaxQuota());
                lastException = new SmsBudgetExceededException(attemptChannel.getMaxQuota(), used);
            } else {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("[SmsFailoverChain] 尝试通过渠道 [{}] 投递短信 -> 驱动: {}, 模板: {}, 签名: {}, 目标号码: {}",
                                attemptChannel.getName(),
                                attemptChannel.resolveProviderCode(),
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
                            log.warn("[SmsFailoverChain] 顺延容灾降级投递成功 -> 主渠道: [{}], 降级渠道: [{}], 驱动: {}, 目标号码: {}",
                                    initialChannel.getName(),
                                    attemptChannel.getName(),
                                    attemptChannel.resolveProviderCode(),
                                    DesensitizeUtil.mobilePhone(request.getPhone()));
                        }
                        return result;
                    }

                    // 厂商未抛异常但显式返回失败，对称释放预扣配额并记入熔断计数
                    releaseQuotaSafely(attemptChannel, request.getPhones().size());
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(attemptChannel.getName());
                    }
                    String rawCode = result != null ? result.getRawCode() : "UNKNOWN";
                    String rawMsg = result != null ? result.getRawMessage() : "Send failed";
                    lastException = new SmsSendException(attemptChannel.getName(), rawCode, rawMsg,
                            SmsResultCode.SMS_SEND_ERROR, rawMsg);
                } catch (Exception ex) {
                    releaseQuotaSafely(attemptChannel, request.getPhones().size());
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(attemptChannel.getName());
                    }
                    lastException = ex;
                }
            }

            // 检查当前渠道是否配置了备用容灾渠道
            String failoverChannelName = currentChannel.getFailoverChannel();
            if (failoverChannelName == null || failoverChannelName.isBlank()) {
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
                        handler.handle(lastAttemptRequest != null ? lastAttemptRequest : request,
                                currentChannel != null ? currentChannel : initialChannel, lastException);
                    } catch (Exception handlerEx) {
                        log.error("[SmsFailoverChain] 失败补偿处理器 [{}] 执行异常: {}",
                                handler.getClass().getName(), handlerEx.getMessage());
                    }
                }
            }
        }

        if (lastException instanceof SmsBudgetExceededException sbee) {
            throw sbee;
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

    /**
     * 在高可用顺延容灾链中执行原生个性化批量发信（统一受熔断器与单渠道配额守卫保护）
     *
     * @param request        已过滤黑名单的批量发信请求
     * @param initialChannel 初始主发信渠道
     * @return 原生个性化批量结果；若链路上存在渠道但当前尝试渠道不支持原生个性化批量，则返回 null 交由上层单条并发降级
     * @throws SmsBudgetExceededException 当主备渠道均触达配额上限时抛出
     */
    public BatchSmsResult executePersonalizedBatch(BatchSmsRequest request, SmsChannel initialChannel) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        BusinessAssert.notNull(initialChannel, "初始渠道 SmsChannel 不能为空");

        Set<String> visitedChannels = new HashSet<>();
        SmsChannel currentChannel = initialChannel;
        Exception lastException = null;
        boolean isPlainText = !StringUtils.hasText(request.getTemplateId()) && StringUtils.hasText(request.getContent());
        int count = request.getPhones().size();

        while (currentChannel != null) {
            final SmsChannel attemptChannel = currentChannel;
            visitedChannels.add(attemptChannel.getName());

            if (circuitBreaker != null && !circuitBreaker.allowRequest(attemptChannel.getName())) {
                lastException = new SmsSendException(attemptChannel.getName(), "CIRCUIT_BREAKER_OPEN",
                        "渠道处于短时熔断开路态: " + attemptChannel.getName(),
                        SmsResultCode.SMS_SEND_ERROR,
                        "渠道处于短时熔断开路态: " + attemptChannel.getName());
            } else {
                String targetTemplateId = isPlainText ? null : attemptChannel.resolveTemplateId(request.getTemplateId());
                String targetSignName = attemptChannel.resolveSignName(request.getTemplateId(), request.getSignName());
                SmsProvider provider = resolveProvider(attemptChannel);
                if (provider == null) {
                    String missingCode = attemptChannel.resolveProviderCode();
                    lastException = new SmsSendException(attemptChannel.getName(), "PROVIDER_NOT_FOUND",
                            "未找到云厂商驱动: " + missingCode, SmsResultCode.SMS_CHANNEL_NOT_FOUND,
                            "未找到云厂商驱动: " + missingCode);
                } else if (isPlainText && !provider.supportsPlainText()) {
                    SmsException unsupportedEx = new SmsException(SmsResultCode.SMS_PARAM_INVALID,
                            "短信厂商 [" + attemptChannel.resolveProviderCode() + "] 仅支持模板发送，不支持无模板纯文本直发");
                    if (visitedChannels.size() == 1) {
                        throw unsupportedEx;
                    }
                    lastException = unsupportedEx;
                } else if (quotaGuard != null && !quotaGuard.tryAcquire(attemptChannel, count)) {
                    long used = quotaGuard.getUsedQuota(attemptChannel);
                    log.warn("[SmsFailoverChain] 个性化批量渠道 [{}] 已达配额上限 (已用: {}, 上限: {})，跳过并尝试顺延备用渠道",
                            attemptChannel.getName(), used, attemptChannel.getMaxQuota());
                    lastException = new SmsBudgetExceededException(attemptChannel.getMaxQuota(), used);
                } else {
                    try {
                        BatchSmsRequest channelRequest = request.withTemplateSignAndChannel(
                                targetTemplateId, targetSignName, attemptChannel.getName());
                        BatchSmsResult nativeResult = provider.sendPersonalizedBatch(channelRequest, attemptChannel);
                        if (nativeResult == null) {
                            // 驱动不支持原生个性化批量发送：归还刚才预扣配额，返回 null 交由上层单条并发链（每条会各自走 execute 扣减配额）
                            releaseQuotaSafely(attemptChannel, count);
                            return null;
                        }
                        if (nativeResult.isSuccess() || nativeResult.getSuccessCount() > 0) {
                            if (circuitBreaker != null) {
                                circuitBreaker.recordSuccess(attemptChannel.getName());
                            }
                            return nativeResult;
                        }
                        releaseQuotaSafely(attemptChannel, count);
                        if (circuitBreaker != null) {
                            circuitBreaker.recordFailure(attemptChannel.getName());
                        }
                        lastException = new SmsSendException(attemptChannel.getName(), "BATCH_FAILED",
                                "个性化批量发信全部失败", SmsResultCode.SMS_SEND_ERROR, "个性化批量发信全部失败");
                    } catch (Exception ex) {
                        releaseQuotaSafely(attemptChannel, count);
                        if (circuitBreaker != null) {
                            circuitBreaker.recordFailure(attemptChannel.getName());
                        }
                        lastException = ex;
                    }
                }
            }

            SmsChannel backup = resolveNextBackupChannel(currentChannel, visitedChannels);
            if (backup == null) {
                break;
            }
            currentChannel = backup;
        }

        if (lastException instanceof SmsBudgetExceededException sbee) {
            throw sbee;
        }
        if (lastException instanceof SmsSendException sse) {
            throw sse;
        }
        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new SmsSendException(initialChannel.getName(), "FAILOVER_EXHAUSTED",
                "个性化批量发信链路全部投递失败", SmsResultCode.SMS_SEND_ERROR, "个性化批量发信链路全部投递失败", lastException);
    }

    private SmsChannel resolveNextBackupChannel(SmsChannel currentChannel, Set<String> visitedChannels) {
        String failoverChannelName = currentChannel.getFailoverChannel();
        if (failoverChannelName == null || failoverChannelName.isBlank()) {
            return null;
        }
        String nextName = failoverChannelName.trim();
        if (visitedChannels.contains(nextName)) {
            log.error("[SmsFailoverChain] 检测到渠道容灾配置环路引用 [{}] -> [{}]，终止顺延降级",
                    currentChannel.getName(), nextName);
            return null;
        }
        SmsChannel backupChannel = channelRegistry != null ? channelRegistry.get(nextName) : null;
        if (backupChannel == null) {
            log.error("[SmsFailoverChain] 渠道 [{}] 配置的容灾备用渠道 [{}] 不存在，终止顺延降级",
                    currentChannel.getName(), nextName);
            return null;
        }
        return backupChannel;
    }

    private void releaseQuotaSafely(SmsChannel channel, int count) {
        if (quotaGuard != null && channel != null && count > 0) {
            quotaGuard.release(channel, count);
        }
    }
}
