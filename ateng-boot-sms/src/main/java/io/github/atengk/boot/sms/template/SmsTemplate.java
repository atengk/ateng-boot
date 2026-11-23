package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.delay.MemorySmsDelayScheduler;
import io.github.atengk.boot.sms.delay.SmsDelayScheduler;
import io.github.atengk.boot.sms.delay.SmsScheduledReceipt;
import io.github.atengk.boot.sms.event.SmsChannelEvictEvent;
import io.github.atengk.boot.sms.event.SmsSendAuditEvent;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.exception.SmsException;
import io.github.atengk.boot.sms.exception.SmsIdempotentException;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.failover.SmsFailoverChain;
import io.github.atengk.boot.sms.guard.*;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.interceptor.SmsSendContext;
import io.github.atengk.boot.sms.interceptor.SmsSendInterceptor;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.limiter.SmsRateLimiter;
import io.github.atengk.boot.sms.loadbalancer.SmsChannelLoadBalancer;
import io.github.atengk.boot.sms.metrics.SmsMetricsRecorder;
import io.github.atengk.boot.sms.metrics.SmsSendOutcome;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.AbstractHttpSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.registry.SmsChannelResolver;
import io.github.atengk.boot.sms.registry.TenantSmsChannelResolver;
import io.github.atengk.boot.sms.support.SmsDesensitizeSupport;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 短信统一操作核心门面
 * 对外暴露高内聚、易于使用的发信 API（单发、群发透明切片、异步投递），屏蔽底层异构云厂商网络与鉴权细节
 * 集成三级级联寻址引擎（SmsContextHolder -> SmsChannelResolver -> default-channel）、多命名渠道注册中心、自动顺延容灾链、平滑加权负载均衡器、渠道健康度熔断器、黑名单前置静默防卫与单日成本预算硬熔断器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsTemplate implements DisposableBean {

    /**
     * 负载均衡虚拟渠道标识
     */
    public static final String LOAD_BALANCE_CHANNEL = "load-balance";

    @Getter
    private final SmsProperties properties;
    private final Map<SmsSupplier, SmsProvider> providerMap = new ConcurrentHashMap<>();

    @Getter
    private final SmsChannelRegistry channelRegistry;

    @Getter
    private final List<SmsChannelResolver> channelResolvers;

    @Getter
    private final List<SmsSendFailureHandler> failureHandlers;

    @Getter
    private final SmsFailoverChain failoverChain;

    @Getter
    private final SmsChannelCircuitBreaker circuitBreaker;

    @Getter
    private final SmsChannelLoadBalancer loadBalancer;

    @Getter
    private final SmsBlacklistProvider blacklistProvider;

    @Getter
    private final DailyBudgetBreaker dailyBudgetBreaker;

    @Getter
    private final SmsIdempotentGuard idempotentGuard;

    @Getter
    private final ApplicationEventPublisher eventPublisher;

    @Getter
    private final Executor executor;

    @Getter
    private final Clock clock;

    @Getter
    private final List<SmsSendInterceptor> sendInterceptors;

    @Getter
    private final SmsRateLimiter rateLimiter;

    @Getter
    private final SmsQuotaGuard quotaGuard;

    @Getter
    private final SmsDelayScheduler delayScheduler;

    private final boolean ownsDelayScheduler;

    @Getter
    private final SmsMetricsRecorder metricsRecorder;

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       SmsIdempotentGuard idempotentGuard,
                       ApplicationEventPublisher eventPublisher,
                       Executor executor,
                       Clock clock,
                       List<SmsSendInterceptor> sendInterceptors,
                       SmsRateLimiter rateLimiter,
                       SmsQuotaGuard quotaGuard,
                       SmsDelayScheduler delayScheduler,
                       SmsMetricsRecorder metricsRecorder) {
        this.properties = properties != null ? properties : new SmsProperties();
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.metricsRecorder = metricsRecorder != null ? metricsRecorder : SmsMetricsRecorder.noop();
        if (providers != null) {
            for (SmsProvider provider : providers) {
                if (provider != null && provider.getSupplier() != null) {
                    this.providerMap.putIfAbsent(provider.getSupplier(), provider);
                }
                if (provider instanceof AbstractHttpSmsProvider httpProvider) {
                    httpProvider.setMetricsRecorder(this.metricsRecorder);
                }
            }
        }
        this.channelRegistry = channelRegistry != null ? channelRegistry : new SmsChannelRegistry(this.properties);
        List<SmsChannelResolver> resolversList = new ArrayList<>();
        if (channelResolvers != null) {
            resolversList.addAll(channelResolvers);
        }
        if (resolversList.isEmpty()) {
            resolversList.add(new TenantSmsChannelResolver(this.channelRegistry));
        }
        AnnotationAwareOrderComparator.sort(resolversList);
        this.channelResolvers = Collections.unmodifiableList(resolversList);

        this.failureHandlers = failureHandlers != null ? Collections.unmodifiableList(new ArrayList<>(failureHandlers)) : Collections.emptyList();
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new SmsChannelCircuitBreaker(this.properties.getGuard(), this.clock);
        this.circuitBreaker.setMetricsRecorder(this.metricsRecorder);
        this.quotaGuard = quotaGuard != null ? quotaGuard : new InMemorySmsQuotaGuard(this.properties.getGuard().getInMemoryMaxEntries(), this.clock);
        this.loadBalancer = loadBalancer != null ? loadBalancer : new SmsChannelLoadBalancer(this.channelRegistry, this.circuitBreaker, this.quotaGuard);
        this.failoverChain = new SmsFailoverChain(this.channelRegistry, providers, this.failureHandlers, this.circuitBreaker, this.quotaGuard, this.metricsRecorder);
        this.channelRegistry.addEvictListener(chName -> {
            this.loadBalancer.reset(chName);
            this.circuitBreaker.reset(chName);
        });
        this.blacklistProvider = blacklistProvider != null ? blacklistProvider : new InMemorySmsBlacklistProvider(this.properties.getGuard().getBlacklist());
        this.dailyBudgetBreaker = dailyBudgetBreaker != null ? dailyBudgetBreaker : new InMemoryDailyBudgetBreaker(this.properties.getGuard(), null, this.clock);
        this.idempotentGuard = idempotentGuard != null ? idempotentGuard : new InMemorySmsIdempotentGuard(this.properties.getGuard().getInMemoryMaxEntries(), this.clock);
        this.eventPublisher = eventPublisher;
        this.executor = executor != null ? executor : AsyncUtil.getExecutor();
        List<SmsSendInterceptor> interceptorList = sendInterceptors != null ? new ArrayList<>(sendInterceptors) : new ArrayList<>();
        interceptorList.removeIf(Objects::isNull);
        AnnotationAwareOrderComparator.sort(interceptorList);
        this.sendInterceptors = Collections.unmodifiableList(interceptorList);
        this.rateLimiter = rateLimiter != null ? rateLimiter : new InMemorySmsRateLimiter(
                this.properties.getRateLimit(), this.properties.getGuard().getInMemoryMaxEntries(), this.clock);
        this.ownsDelayScheduler = delayScheduler == null;
        this.delayScheduler = delayScheduler != null ? delayScheduler : new MemorySmsDelayScheduler(this.properties.getDelay(), this.clock);
        this.delayScheduler.bindDispatchers(this::send, this::sendBatch);
    }

    @Override
    public void destroy() {
        if (ownsDelayScheduler && delayScheduler instanceof DisposableBean disposable) {
            try {
                disposable.destroy();
            } catch (Exception ex) {
                log.warn("[SmsTemplate] 停机销毁内置默认延迟调度器异常: {}", ex.getMessage());
            }
        }
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       SmsIdempotentGuard idempotentGuard,
                       ApplicationEventPublisher eventPublisher,
                       Executor executor,
                       Clock clock,
                       List<SmsSendInterceptor> sendInterceptors,
                       SmsRateLimiter rateLimiter,
                       SmsQuotaGuard quotaGuard) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider,
                dailyBudgetBreaker, circuitBreaker, loadBalancer, idempotentGuard, eventPublisher, executor, clock,
                sendInterceptors, rateLimiter, quotaGuard, null, null);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       SmsIdempotentGuard idempotentGuard,
                       ApplicationEventPublisher eventPublisher,
                       Executor executor,
                       Clock clock,
                       List<SmsSendInterceptor> sendInterceptors,
                       SmsRateLimiter rateLimiter) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider,
                dailyBudgetBreaker, circuitBreaker, loadBalancer, idempotentGuard, eventPublisher, executor, clock,
                sendInterceptors, rateLimiter, null);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       SmsIdempotentGuard idempotentGuard,
                       ApplicationEventPublisher eventPublisher,
                       Executor executor,
                       Clock clock) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider,
                dailyBudgetBreaker, circuitBreaker, loadBalancer, idempotentGuard, eventPublisher, executor, clock, null, null);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       SmsIdempotentGuard idempotentGuard,
                       ApplicationEventPublisher eventPublisher,
                       Executor executor) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider, dailyBudgetBreaker, circuitBreaker, loadBalancer, idempotentGuard, eventPublisher, executor, null);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       SmsChannelCircuitBreaker circuitBreaker,
                       SmsChannelLoadBalancer loadBalancer,
                       Executor executor) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider, dailyBudgetBreaker, circuitBreaker, loadBalancer, null, null, executor);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       List<SmsSendFailureHandler> failureHandlers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       Executor executor) {
        this(properties, providers, channelRegistry, channelResolvers, failureHandlers, blacklistProvider, dailyBudgetBreaker, null, null, executor);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       Executor executor) {
        this(properties, providers, channelRegistry, channelResolvers, null, blacklistProvider, dailyBudgetBreaker, executor);
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       Executor executor) {
        this(properties, providers, null, null, null, blacklistProvider, dailyBudgetBreaker, executor);
    }

    public SmsTemplate(SmsProperties properties, List<SmsProvider> providers) {
        this(properties, providers, null, null, null, null, null, null);
    }

    /**
     * 动态注册或更新渠道
     *
     * @param channel 短信渠道
     */
    public void registerChannel(SmsChannel channel) {
        channelRegistry.register(channel);
    }

    /**
     * 动态注销渠道
     *
     * @param channelName 渠道名称
     * @return 被注销的 SmsChannel
     */
    public SmsChannel unregisterChannel(String channelName) {
        return channelRegistry.unregister(channelName);
    }

    /**
     * 主动驱逐指定动态渠道缓存（并联动清理负载均衡权重与熔断健康态）
     *
     * @param channelName 渠道名称
     */
    public void evictChannelCache(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        String trimmed = channelName.trim();
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SmsChannelEvictEvent(this, trimmed));
            return;
        }
        channelRegistry.evictDynamic(trimmed);
        if (loadBalancer != null) {
            loadBalancer.reset(trimmed);
        }
        if (circuitBreaker != null) {
            circuitBreaker.reset(trimmed);
        }
    }

    /**
     * 全量驱逐清空动态渠道缓存（并联动重置负载均衡权重与全部熔断健康态）
     */
    public void evictAllChannelCache() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SmsChannelEvictEvent(this));
            return;
        }
        channelRegistry.evictAllDynamic();
        if (loadBalancer != null) {
            loadBalancer.reset();
        }
        if (circuitBreaker != null) {
            circuitBreaker.resetAll();
        }
    }

    /**
     * 发送单条短信
     *
     * @param request 发信请求值对象
     * @return SmsResult 发送结果
     * @throws SmsSendException           当发信失败或所有容灾渠道均失败时抛出
     * @throws SmsBudgetExceededException 当达到单日成本预算上限时触发硬熔断抛出
     */
    public SmsResult send(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.hasText(request.getPhone(), "目标手机号不能为空");

        long startTime = clock.millis();
        SmsRequest effectiveRequest = request;
        SmsChannel channel = null;
        SmsResult result = null;
        Throwable failure = null;
        String idempotentKey = null;
        boolean lockAcquired = false;
        SmsSendContext sendContext = null;
        int executedInterceptors = 0;
        String blockCategory = null;

        try {
            // 1. 三级级联解析目标发信渠道并校检模板/纯文本发送模式
            channel = resolveChannel(effectiveRequest.getChannel());
            String effectiveTemplateId = validateAndResolveMode(channel, effectiveRequest.getTemplateId(), effectiveRequest.getContent());

            // 2. 统一发送拦截链前置钩子（含高优先级安全沙箱拦截/重定向/纯日志模拟）
            if (!sendInterceptors.isEmpty()) {
                sendContext = SmsSendContext.forSingle(effectiveRequest, channel);
                for (SmsSendInterceptor interceptor : sendInterceptors) {
                    executedInterceptors++;
                    if (!interceptor.preSend(sendContext)) {
                        effectiveRequest = sendContext.getRequest() != null ? sendContext.getRequest() : effectiveRequest;
                        channel = sendContext.getChannel() != null ? sendContext.getChannel() : channel;
                        if (sendContext.getShortCircuitResult() != null) {
                            result = sendContext.getShortCircuitResult();
                        } else {
                            String reason = sendContext.getBlockReason() != null ? sendContext.getBlockReason() : "被发送拦截器阻断";
                            result = SmsResult.blocked(channel.getName(), reason);
                        }
                        blockCategory = (result != null && result.getDeliveryStatus() == SmsDeliveryStatus.MOCKED) ? "sandbox" : "interceptor";
                        invokePostSend(executedInterceptors, sendContext, result);
                        return result;
                    }
                }
                if (sendContext.getRequest() != null) {
                    effectiveRequest = sendContext.getRequest();
                }
                channel = syncChannelAfterPreSend(sendContext, channel, effectiveRequest.getChannel());
                effectiveTemplateId = validateAndResolveMode(channel, effectiveRequest.getTemplateId(), effectiveRequest.getContent());
            }

            // 3. 出站黑名单前置静默拦截防卫（多号码逐一校验，防漏检穿透）
            if (properties.getGuard().isBlacklistEnabled() && blacklistProvider != null) {
                for (String p : effectiveRequest.getPhones()) {
                    if (blacklistProvider.isBlacklisted(p)) {
                        String reason = blacklistProvider.getReason(p);
                        if (log.isWarnEnabled()) {
                            log.warn("[SmsTemplate] 目标手机号 [{}] 命中出站黑名单，前置静默拦截 (原因: {})",
                                    DesensitizeUtil.mobilePhone(p), reason);
                        }
                        result = SmsResult.blocked(channel.getName(), reason);
                        blockCategory = "blacklist";
                        invokePostSend(executedInterceptors, sendContext, result);
                        return result;
                    }
                }
            }

            // 4. 营销短信夜间免打扰时段静默拦截 (验证码与 urgent 短信自动豁免)
            if (properties.getGuard().isQuietHoursEnabled() && !effectiveRequest.isUrgent() && isInQuietHours()) {
                String quietReason = "夜间免打扰时段 (" + properties.getGuard().getQuietHoursStart() + "-" + properties.getGuard().getQuietHoursEnd() + ") 限制发信";
                if (log.isWarnEnabled()) {
                    log.warn("[SmsTemplate] 目标手机号 [{}] 触发夜间免打扰拦截 ({})",
                            DesensitizeUtil.mobilePhone(effectiveRequest.getPhone()), quietReason);
                }
                result = SmsResult.blocked(channel.getName(), quietReason);
                blockCategory = "quiet_hours";
                invokePostSend(executedInterceptors, sendContext, result);
                return result;
            }

            // 5. 非验证码业务通知单号滑窗频控隔离防卫 (notify: 命名空间)
            if (properties.getGuard().isNotifyRateLimitEnabled()
                    && rateLimiter != null
                    && !effectiveRequest.isSkipRateLimit()
                    && !effectiveRequest.isUrgent()) {
                for (String p : effectiveRequest.getPhones()) {
                    rateLimiter.acquire("notify", p, null);
                }
            }

            // 6. 出站发信幂等防重卫士拦截
            if (properties.getGuard().isIdempotentEnabled() && idempotentGuard != null) {
                idempotentKey = idempotentGuard.generateKey(effectiveRequest);
                boolean acquired = idempotentGuard.acquireLock(idempotentKey, properties.getGuard().getIdempotentWindow());
                if (!acquired) {
                    if (log.isWarnEnabled()) {
                        log.warn("[SmsTemplate] 发信请求命中出站幂等窗口防重拦截 -> 手机号: {}, 幂等键: {}",
                                DesensitizeUtil.mobilePhone(effectiveRequest.getPhone()), idempotentKey);
                    }
                    throw new SmsIdempotentException(effectiveRequest.getPhone(), idempotentKey);
                }
                lockAcquired = true;
            }

            // 7. 单日成本预算硬熔断防卫
            if (dailyBudgetBreaker != null) {
                dailyBudgetBreaker.checkAndIncrement(effectiveRequest.getPhones().size());
            }

            // 8. 经由高可用顺延容灾链执行物理发信
            if (log.isDebugEnabled()) {
                log.debug("[SmsTemplate] 准备发送短信 -> 目标号码: {}, 初始渠道: {}, 驱动: {}, 模板: {}",
                        DesensitizeUtil.mobilePhone(effectiveRequest.getPhone()),
                        channel.getName(),
                        channel.resolveProviderCode(),
                        effectiveTemplateId != null ? effectiveTemplateId : "[PLAIN_TEXT]");
            }

            result = failoverChain.execute(effectiveRequest, channel);

            // 若物理发信显式返回失败，释放已持有的幂等锁以允许业务重试
            if (result != null && !result.isSuccess() && lockAcquired && idempotentGuard != null) {
                idempotentGuard.releaseLock(idempotentKey);
            }

            invokePostSend(executedInterceptors, sendContext, result);
            return result;
        } catch (Throwable ex) {
            failure = ex;
            // 恪守防御性锁释放范式：仅当当前线程真实持有锁且遭遇底层异常时才触发补偿释放，严禁释放未持有的锁
            if (lockAcquired && idempotentGuard != null && !(ex instanceof SmsIdempotentException)) {
                idempotentGuard.releaseLock(idempotentKey);
            }
            invokeOnError(executedInterceptors, sendContext, ex);
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new SmsSendException(channel != null ? channel.getName() : effectiveRequest.getChannel(),
                    "UNEXPECTED_ERROR", ex.getMessage(), SmsResultCode.SMS_SEND_ERROR, ex.getMessage(), ex);
        } finally {
            long cost = clock.millis() - startTime;
            recordMetricsForOutcome(channel, effectiveRequest.getChannel(), blockCategory,
                    result != null && result.isSuccess(), failure, cost);
            // 9. 广播全链路出站发信审计事件（按配置脱敏内容与敏感变量）
            if (eventPublisher != null) {
                String channelName = channel != null ? channel.getName() : effectiveRequest.getChannel();
                SmsSupplier supplier = resolveEffectiveSupplier(channel);
                SmsRequest auditRequest = properties.getGuard().isLogDesensitizeContent()
                        ? SmsDesensitizeSupport.sanitizeForAudit(effectiveRequest, channel, properties.getGuard().getSensitiveParamKeys())
                        : effectiveRequest;
                try {
                    eventPublisher.publishEvent(new SmsSendAuditEvent(
                            this, auditRequest, channelName, supplier, result, failure, cost, Instant.now(clock)
                    ));
                } catch (Exception pubEx) {
                    log.error("[SmsTemplate] 广播发信审计事件异常: {}", pubEx.getMessage());
                }
            }
        }
    }

    /**
     * 异步发送单条短信
     *
     * @param request 发信请求值对象
     * @return CompletableFuture 异步结果
     */
    public CompletableFuture<SmsResult> sendAsync(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> send(request)), executor);
    }

    /**
     * 本地事务感知发信
     * 仅在 Spring 本地事务成功 COMMIT 后触发真实投递，事务回滚时自动安全取消，彻底防御幽灵短信
     * 若当前线程无活动事务，则降级为直接同步发送
     *
     * @param request 发信请求值对象
     */
    public void sendAfterCommit(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Runnable action = AsyncContext.wrap(() -> {
                try {
                    send(request);
                } catch (Exception ex) {
                    log.error("[SmsTemplate] 事务提交后发信异常 (目标号码: {}): {}",
                            DesensitizeUtil.mobilePhone(request.getPhone()), ex.getMessage(), ex);
                }
            });
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            if (log.isDebugEnabled()) {
                log.debug("[SmsTemplate] 检测到活动事务，已注册事务提交后投递钩子 (目标号码: {})",
                        DesensitizeUtil.mobilePhone(request.getPhone()));
            }
        } else {
            send(request);
        }
    }

    /**
     * 本地事务感知异步发信
     * 仅在 Spring 本地事务成功 COMMIT 后触发受管异步发信，事务回滚时自动安全取消
     * 若当前线程无活动事务，则降级为直接异步发送
     *
     * @param request 发信请求值对象
     * @return CompletableFuture 异步结果
     */
    public CompletableFuture<SmsResult> sendAsyncAfterCommit(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return registerAsyncAfterCommit(
                    () -> CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> send(request)), executor),
                    request.getChannel(),
                    "目标号码: " + DesensitizeUtil.mobilePhone(request.getPhone())
            );
        } else {
            return sendAsync(request);
        }
    }

    /**
     * 批量营销短信发送
     * 结合 Core 模块 BatchSliceUtil 对海量手机号按云厂商上限（腾讯 200、华为 500、阿里 1000）自动切片，
     * 经由受管线程池受控并发投递、顺延容灾降级并聚合汇总为 BatchSmsResult
     *
     * @param request 批量发信请求
     * @return 批量聚合结果 BatchSmsResult
     * @throws SmsBudgetExceededException 当达到单日成本预算上限时触发硬熔断抛出
     */
    public BatchSmsResult sendBatch(BatchSmsRequest request) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        BusinessAssert.notEmpty(request.getPhones(), "目标手机号列表不能为空");

        long startTime = clock.millis();
        BatchSmsRequest effectiveRequest = request;
        SmsChannel channel = null;
        BatchSmsResult batchResult = null;
        Throwable failure = null;
        String idempotentKey = null;
        boolean lockAcquired = false;
        SmsSendContext sendContext = null;
        int executedInterceptors = 0;
        String blockCategory = null;

        try {
            // 1. 三级级联解析目标渠道
            SmsChannel targetChannel = resolveChannel(effectiveRequest.getChannel());
            channel = targetChannel;

            String effectiveTemplateId = validateAndResolveMode(targetChannel, effectiveRequest.getTemplateId(), effectiveRequest.getContent());

            // 1.5 统一发送拦截链前置钩子（含高优先级安全沙箱拦截/去重重定向/纯日志模拟）
            if (!sendInterceptors.isEmpty()) {
                sendContext = SmsSendContext.forBatch(effectiveRequest, targetChannel);
                for (SmsSendInterceptor interceptor : sendInterceptors) {
                    executedInterceptors++;
                    if (!interceptor.preSend(sendContext)) {
                        effectiveRequest = sendContext.getBatchRequest() != null ? sendContext.getBatchRequest() : effectiveRequest;
                        targetChannel = sendContext.getChannel() != null ? sendContext.getChannel() : targetChannel;
                        channel = targetChannel;
                        if (sendContext.getShortCircuitBatchResult() != null) {
                            batchResult = sendContext.getShortCircuitBatchResult();
                        } else {
                            String reason = sendContext.getBlockReason() != null ? sendContext.getBlockReason() : "被发送拦截器阻断";
                            batchResult = BatchSmsResult.of(
                                    effectiveRequest.getPhones().size(),
                                    0,
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    List.of(SmsResult.blocked(targetChannel.getName(), reason))
                            );
                        }
                        SmsResult rep = representativeBatchResult(batchResult, targetChannel);
                        blockCategory = (rep != null && rep.getDeliveryStatus() == SmsDeliveryStatus.MOCKED) ? "sandbox" : "interceptor";
                        invokePostSend(executedInterceptors, sendContext, rep);
                        return batchResult;
                    }
                }
                if (sendContext.getBatchRequest() != null) {
                    effectiveRequest = sendContext.getBatchRequest();
                }
                targetChannel = syncChannelAfterPreSend(sendContext, targetChannel, effectiveRequest.getChannel());
                channel = targetChannel;
                effectiveTemplateId = validateAndResolveMode(targetChannel, effectiveRequest.getTemplateId(), effectiveRequest.getContent());
            }

            // 2. 营销短信夜间免打扰时段静默拦截 (urgent 批量短信自动豁免)
            if (properties.getGuard().isQuietHoursEnabled() && !effectiveRequest.isUrgent() && isInQuietHours()) {
                String quietReason = "夜间免打扰时段 (" + properties.getGuard().getQuietHoursStart() + "-" + properties.getGuard().getQuietHoursEnd() + ") 限制发信";
                if (log.isWarnEnabled()) {
                    log.warn("[SmsTemplate] 批量发信请求触发夜间免打扰拦截 (总号码数: {}, 渠道: {}) -> {}",
                            effectiveRequest.getPhones().size(), channel.getName(), quietReason);
                }
                batchResult = BatchSmsResult.of(
                        effectiveRequest.getPhones().size(),
                        0,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        List.of(SmsResult.blocked(channel.getName(), quietReason))
                );
                blockCategory = "quiet_hours";
                invokePostSend(executedInterceptors, sendContext, representativeBatchResult(batchResult, targetChannel));
                return batchResult;
            }

            // 3. 出站黑名单前置静默拦截
            List<String> rawPhones = effectiveRequest.getPhones();
            List<String> allowedPhones = new ArrayList<>(rawPhones.size());
            List<String> blacklistedPhones = new ArrayList<>();

            boolean checkBlacklist = properties.getGuard().isBlacklistEnabled() && blacklistProvider != null;
            for (String phone : rawPhones) {
                if (checkBlacklist && blacklistProvider.isBlacklisted(phone)) {
                    blacklistedPhones.add(phone);
                    if (log.isWarnEnabled()) {
                        log.warn("[SmsTemplate] 手机号 [{}] 命中出站黑名单，前置静默拦截 (原因: {})",
                                DesensitizeUtil.mobilePhone(phone),
                                blacklistProvider.getReason(phone));
                    }
                } else {
                    allowedPhones.add(phone);
                }
            }

            // 若全部号码均处于黑名单中，直接阻断外发并赋值给 batchResult 确保审计完整
            if (allowedPhones.isEmpty()) {
                batchResult = BatchSmsResult.of(
                        rawPhones.size(),
                        0,
                        Collections.emptyList(),
                        blacklistedPhones,
                        List.of(SmsResult.blocked(channel.getName(), "全部目标手机号均处于出站黑名单中"))
                );
                blockCategory = "blacklist";
                invokePostSend(executedInterceptors, sendContext, representativeBatchResult(batchResult, targetChannel));
                return batchResult;
            }

            // 4. 批量出站幂等防重保护（仅当显式提供 bizId 时生效）
            if (properties.getGuard().isIdempotentEnabled() && idempotentGuard != null) {
                idempotentKey = idempotentGuard.generateKey(effectiveRequest);
                if (idempotentKey != null) {
                    boolean acquired = idempotentGuard.acquireLock(idempotentKey, properties.getGuard().getIdempotentWindow());
                    if (!acquired) {
                        if (log.isWarnEnabled()) {
                            log.warn("[SmsTemplate] 批量发信请求命中出站幂等窗口防重拦截 -> 首号: {}, 幂等键: {}",
                                    DesensitizeUtil.mobilePhone(rawPhones.get(0)), idempotentKey);
                        }
                        throw new SmsIdempotentException(rawPhones.get(0), idempotentKey);
                    }
                    lockAcquired = true;
                }
            }

            // 5. 单日成本预算硬熔断防卫（基于实际拟放行发信量）
            if (dailyBudgetBreaker != null) {
                dailyBudgetBreaker.checkAndIncrement(allowedPhones.size());
            }

            // 6. 个性化批量发信分支（每个号码拥有独立的动态参数模型，统一受容灾链、熔断器与单渠道配额守卫治理）
            if (effectiveRequest.isPersonalized()) {
                Map<String, SmsParam> filteredParams = new LinkedHashMap<>();
                for (String p : allowedPhones) {
                    filteredParams.put(p, effectiveRequest.getPhoneParams().get(p));
                }
                BatchSmsRequest filteredRequest = BatchSmsRequest.builder()
                        .phones(allowedPhones)
                        .templateId(effectiveRequest.getTemplateId())
                        .content(effectiveRequest.getContent())
                        .params(effectiveRequest.getParams())
                        .phoneParams(filteredParams)
                        .signName(effectiveRequest.getSignName())
                        .channel(channel.getName())
                        .bizId(effectiveRequest.getBizId())
                        .urgent(effectiveRequest.isUrgent())
                        .build();

                BatchSmsResult nativeResult = failoverChain.executePersonalizedBatch(filteredRequest, targetChannel);
                if (nativeResult != null) {
                    if (blacklistedPhones.isEmpty()) {
                        batchResult = nativeResult;
                    } else {
                        batchResult = BatchSmsResult.of(
                                rawPhones.size(),
                                nativeResult.getSuccessCount(),
                                nativeResult.getFailedPhones(),
                                blacklistedPhones,
                                nativeResult.getBatchResults()
                        );
                    }
                    releaseIdempotentOnFailure(batchResult.isSuccess(), batchResult.getSuccessCount(), lockAcquired, idempotentKey);
                    invokePostSend(executedInterceptors, sendContext, representativeBatchResult(batchResult, targetChannel));
                    return batchResult;
                }

                // 云厂商未实现原生个性化批量发送，降级为受管线程池并发投递单条发信
                final SmsChannel finalTargetChannel = targetChannel;
                List<CompletableFuture<SliceExecutionResult>> futures = new ArrayList<>(allowedPhones.size());
                for (String phone : allowedPhones) {
                    SmsParam phoneParam = effectiveRequest.getPhoneParams().get(phone);
                    SmsRequest singleReq = SmsRequest.builder()
                            .phone(phone)
                            .templateId(effectiveRequest.getTemplateId())
                            .content(effectiveRequest.getContent())
                            .params(phoneParam != null ? phoneParam : effectiveRequest.getParams())
                            .signName(effectiveRequest.getSignName())
                            .channel(channel.getName())
                            .bizId(effectiveRequest.getBizId())
                            .urgent(effectiveRequest.isUrgent())
                            .build();

                    Supplier<SliceExecutionResult> singleTask = () -> {
                        try {
                            SmsResult res = failoverChain.execute(singleReq, finalTargetChannel);
                            return new SliceExecutionResult(List.of(phone), res, null);
                        } catch (Exception ex) {
                            log.error("[SmsTemplate] 个性化批次号码投递异常 -> 号码: {}, 错误: {}",
                                    DesensitizeUtil.mobilePhone(phone), ex.getMessage());
                            return new SliceExecutionResult(List.of(phone), null, ex);
                        }
                    };
                    futures.add(CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(singleTask), executor));
                }

                batchResult = awaitAndAggregateSlices(futures, rawPhones.size(), blacklistedPhones, targetChannel);
                releaseIdempotentOnFailure(batchResult.isSuccess(), batchResult.getSuccessCount(), lockAcquired, idempotentKey);
                invokePostSend(executedInterceptors, sendContext, representativeBatchResult(batchResult, targetChannel));
                return batchResult;
            }

            // 7. 依据厂商上限使用 BatchSliceUtil 透明切片
            int sliceLimit = resolveChannelBatchLimit(channel);
            List<List<String>> slices = BatchSliceUtil.slice(allowedPhones, sliceLimit);

            if (log.isDebugEnabled()) {
                log.debug("[SmsTemplate] 批量短信分片投递 -> 渠道: {}, 驱动: {}, 原始号码数: {}, 放行数: {}, 黑名单拦截: {}, 切片数: {}, 单批上限: {}",
                        channel.getName(), channel.resolveProviderCode(), rawPhones.size(), allowedPhones.size(),
                        blacklistedPhones.size(), slices.size(), sliceLimit);
            }

            // 8. 通过 Core 受管异步线程池设施受控并发调度并在各切片批次内部支持顺延容灾
            final SmsChannel finalTargetChannel = targetChannel;
            List<CompletableFuture<SliceExecutionResult>> futures = new ArrayList<>(slices.size());
            for (List<String> slice : slices) {
                SmsRequest sliceRequest = SmsRequest.builder()
                        .phones(slice)
                        .templateId(effectiveRequest.getTemplateId())
                        .content(effectiveRequest.getContent())
                        .params(effectiveRequest.getParams())
                        .signName(effectiveRequest.getSignName())
                        .channel(channel.getName())
                        .bizId(effectiveRequest.getBizId())
                        .urgent(effectiveRequest.isUrgent())
                        .build();

                Supplier<SliceExecutionResult> sliceTask = () -> {
                    try {
                        SmsResult res = failoverChain.execute(sliceRequest, finalTargetChannel);
                        return new SliceExecutionResult(slice, res, null);
                    } catch (Exception ex) {
                        log.error("[SmsTemplate] 批次切片投递异常 -> 批次号码数: {}, 错误: {}", slice.size(), ex.getMessage());
                        return new SliceExecutionResult(slice, null, ex);
                    }
                };

                futures.add(CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(sliceTask), executor));
            }

            batchResult = awaitAndAggregateSlices(futures, rawPhones.size(), blacklistedPhones, targetChannel);
            releaseIdempotentOnFailure(batchResult.isSuccess(), batchResult.getSuccessCount(), lockAcquired, idempotentKey);
            invokePostSend(executedInterceptors, sendContext, representativeBatchResult(batchResult, targetChannel));
            return batchResult;
        } catch (Throwable ex) {
            failure = ex;
            if (lockAcquired && idempotentGuard != null && !(ex instanceof SmsIdempotentException)) {
                idempotentGuard.releaseLock(idempotentKey);
            }
            invokeOnError(executedInterceptors, sendContext, ex);
            if (batchResult == null) {
                batchResult = buildFailureBatchResult(effectiveRequest, channel, ex);
            }
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new SmsSendException(channel != null ? channel.getName() : effectiveRequest.getChannel(),
                    "BATCH_UNEXPECTED_ERROR", ex.getMessage(), SmsResultCode.SMS_SEND_ERROR, ex.getMessage(), ex);
        } finally {
            long cost = clock.millis() - startTime;
            boolean batchOk = batchResult != null && (batchResult.isSuccess() || batchResult.getSuccessCount() > 0);
            recordMetricsForOutcome(channel, effectiveRequest.getChannel(), blockCategory,
                    batchOk, failure, cost);
            // 9. 广播全链路出站批量发信审计事件（按配置脱敏内容与敏感变量）
            if (eventPublisher != null) {
                String channelName = channel != null ? channel.getName() : effectiveRequest.getChannel();
                SmsSupplier supplier = resolveEffectiveSupplier(channel);
                BatchSmsRequest auditRequest = properties.getGuard().isLogDesensitizeContent()
                        ? SmsDesensitizeSupport.sanitizeForAudit(effectiveRequest, channel, properties.getGuard().getSensitiveParamKeys())
                        : effectiveRequest;
                try {
                    eventPublisher.publishEvent(new SmsSendAuditEvent(
                            this, auditRequest, channelName, supplier, batchResult, failure, cost, Instant.now(clock)
                    ));
                } catch (Exception pubEx) {
                    log.error("[SmsTemplate] 广播批量发信审计事件异常: {}", pubEx.getMessage());
                }
            }
        }
    }

    private BatchSmsResult awaitAndAggregateSlices(List<CompletableFuture<SliceExecutionResult>> futures,
                                                   int totalCount,
                                                   List<String> blacklistedPhones,
                                                   SmsChannel channel) {
        List<SliceExecutionResult> execResults;
        try {
            execResults = AsyncUtil.allOf(futures, resolveBatchTimeout());
        } catch (BusinessException ex) {
            throw new SmsSendException(channel.getName(), "BATCH_AGGREGATION_TIMEOUT",
                    "批量短信分片并发聚合异常或超时: " + ex.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, ex.getMessage(), ex);
        }

        SmsSupplier effectiveSupplier = resolveEffectiveSupplier(channel);
        int successCount = 0;
        List<String> failedPhones = new ArrayList<>();
        List<SmsResult> batchResults = new ArrayList<>(execResults.size());

        for (SliceExecutionResult execRes : execResults) {
            if (execRes.exception != null) {
                failedPhones.addAll(execRes.slicePhones);
                String rawCode = execRes.exception instanceof SmsSendException sse ? sse.getRawCode() : "BATCH_SEND_ERROR";
                batchResults.add(SmsResult.failure(channel.getName(), effectiveSupplier, rawCode, execRes.exception.getMessage()));
            } else if (execRes.result != null && execRes.result.isSuccess()) {
                successCount += execRes.slicePhones.size();
                batchResults.add(execRes.result);
            } else {
                failedPhones.addAll(execRes.slicePhones);
                batchResults.add(execRes.result != null
                        ? execRes.result
                        : SmsResult.failure(channel.getName(), effectiveSupplier, "UNKNOWN", "Unknown error"));
            }
        }

        return BatchSmsResult.of(totalCount, successCount, failedPhones, blacklistedPhones, batchResults);
    }

    private BatchSmsResult buildFailureBatchResult(BatchSmsRequest request, SmsChannel channel, Throwable ex) {
        String errChannel = channel != null ? channel.getName() : request.getChannel();
        SmsSupplier errSupplier = resolveEffectiveSupplier(channel);
        String rawCode = ex instanceof SmsSendException sse && sse.getRawCode() != null
                ? sse.getRawCode()
                : ex.getClass().getSimpleName();
        return BatchSmsResult.of(
                request.getPhones().size(),
                0,
                request.getPhones(),
                Collections.emptyList(),
                List.of(SmsResult.failure(errChannel, errSupplier, rawCode, ex.getMessage()))
        );
    }

    private Duration resolveBatchTimeout() {
        Duration configured = properties.getGuard() != null ? properties.getGuard().getBatchTimeout() : null;
        return (configured != null && !configured.isNegative() && !configured.isZero())
                ? configured
                : Duration.ofSeconds(30);
    }

    /**
     * 异步批量发送短信
     *
     * @param request 批量发信请求
     * @return CompletableFuture 异步批量结果
     */
    public CompletableFuture<BatchSmsResult> sendBatchAsync(BatchSmsRequest request) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendBatch(request)), executor);
    }

    /**
     * 本地事务感知批量发信
     * 仅在 Spring 本地事务成功 COMMIT 后触发批量发信，事务回滚时自动安全取消
     * 若当前线程无活动事务，则降级为直接同步发送
     *
     * @param request 批量发信请求
     */
    public void sendBatchAfterCommit(BatchSmsRequest request) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Runnable action = AsyncContext.wrap(() -> {
                try {
                    sendBatch(request);
                } catch (Exception ex) {
                    log.error("[SmsTemplate] 事务提交后批量发信异常 (总号码数: {}): {}",
                            request.getPhones().size(), ex.getMessage(), ex);
                }
            });
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            if (log.isDebugEnabled()) {
                log.debug("[SmsTemplate] 检测到活动事务，已注册事务提交后批量投递钩子 (总号码数: {})",
                        request.getPhones().size());
            }
        } else {
            sendBatch(request);
        }
    }

    /**
     * 本地事务感知异步批量发信
     * 仅在 Spring 本地事务成功 COMMIT 后触发受管异步批量发信，事务回滚时自动安全取消
     * 若当前线程无活动事务，则降级为直接异步批量发送
     *
     * @param request 批量发信请求
     * @return CompletableFuture 异步批量结果
     */
    public CompletableFuture<BatchSmsResult> sendBatchAsyncAfterCommit(BatchSmsRequest request) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return registerAsyncAfterCommit(
                    () -> CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendBatch(request)), executor),
                    request.getChannel(),
                    "总号码数: " + (request.getPhones() != null ? request.getPhones().size() : 0)
            );
        } else {
            return sendBatchAsync(request);
        }
    }

    /**
     * 注册本地事务提交后受管异步任务钩子
     * 统一收敛事务同步器生命周期、AsyncContext 现场绑定与终态补偿，杜绝 Log-and-Throw 双重报错与样板代码重复
     *
     * @param asyncSupplier 异步任务供给器
     * @param channelName   渠道名称（用于事务回滚异常构造）
     * @param description   任务业务标识（用于日志脱敏与异常提示）
     * @param <T>           结果泛型
     * @return 绑定的 CompletableFuture 结果
     */
    private <T> CompletableFuture<T> registerAsyncAfterCommit(
            Supplier<CompletableFuture<T>> asyncSupplier,
            String channelName,
            String description) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = AsyncContext.wrap(() -> {
            asyncSupplier.get().whenComplete((res, ex) -> {
                if (ex != null) {
                    log.warn("[SmsTemplate] 事务提交后受管异步发信失败 ({}): {}", description, ex.getMessage());
                    future.completeExceptionally(ex);
                } else {
                    future.complete(res);
                }
            });
        });
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED && !future.isDone()) {
                    future.completeExceptionally(new SmsSendException(
                            channelName != null ? channelName : "default",
                            "TRANSACTION_ROLLED_BACK",
                            "事务回滚，取消事务后异步发信: " + description,
                            SmsResultCode.SMS_SEND_ERROR,
                            "事务回滚，取消发信"
                    ));
                }
            }
        });
        if (log.isDebugEnabled()) {
            log.debug("[SmsTemplate] 检测到活动事务，已注册事务提交后异步发信钩子 ({})", description);
        }
        return future;
    }

    /**
     * 根据云厂商类型解析单批最大支持的手机号切片上限
     * 腾讯云 200，华为云 500，阿里云 1000，其他默认 1000
     *
     * @param supplier 云厂商类型
     * @return 单批最大手机号数量
     */
    public int resolveBatchLimit(SmsSupplier supplier) {
        return supplier != null ? supplier.getBatchLimit() : 1000;
    }

    /**
     * 根据渠道及其绑定驱动解析单批最大支持的手机号切片上限
     *
     * @param channel 目标短信渠道
     * @return 单批最大手机号数量
     */
    public int resolveChannelBatchLimit(SmsChannel channel) {
        if (channel == null) {
            return 1000;
        }
        SmsProvider provider = failoverChain.resolveProvider(channel);
        if (provider != null) {
            return Math.max(1, provider.getBatchLimit());
        }
        return resolveBatchLimit(channel.getSupplier());
    }

    /**
     * 三级级联决策目标发信渠道名称：
     * 0. 显式入参 > 1. 线程上下文 (SmsContextHolder) > 2. 多租户/业务 SPI (SmsChannelResolver) > 3. 全局默认配置 (default-channel)
     *
     * @param explicitChannel 显式传入的渠道名称（若非空则直接采纳）
     * @return 最终决策的目标渠道名称
     */
    public String resolveChannelName(String explicitChannel) {
        // 0. 显式请求入参优先
        if (explicitChannel != null && !explicitChannel.isBlank()) {
            return explicitChannel.trim();
        }

        // 1. 第一级：显式编程式线程上下文 SmsContextHolder.getChannel()
        String threadChannel = SmsContextHolder.getChannel();
        if (threadChannel != null && !threadChannel.isBlank()) {
            return threadChannel.trim();
        }

        // 2. 第二级：多租户/业务 SPI 解析器列表
        if (channelResolvers != null) {
            for (SmsChannelResolver resolver : channelResolvers) {
                if (resolver != null) {
                    String resolved = resolver.resolveChannel();
                    if (resolved != null && !resolved.isBlank()) {
                        return resolved.trim();
                    }
                }
            }
        }

        // 3. 第三级：全局默认配置兜底渠道
        String defaultChannel = properties.getDefaultChannel();
        if (defaultChannel != null && !defaultChannel.isBlank()) {
            return defaultChannel.trim();
        }

        return "mock";
    }

    /**
     * 根据渠道名称解析短信渠道对象（执行三级级联寻址）
     *
     * @param channelName 渠道名称（可为 null，为 null 时级联寻址）
     * @return SmsChannel 渠道对象
     */
    public SmsChannel resolveChannel(String channelName) {
        String targetName = resolveChannelName(channelName);

        if (LOAD_BALANCE_CHANNEL.equalsIgnoreCase(targetName)) {
            SmsChannel selected = loadBalancer != null ? loadBalancer.selectChannel() : null;
            BusinessAssert.notNull(selected, "负载均衡未找到可用短信渠道");
            return selected;
        }

        SmsChannel channel = channelRegistry.get(targetName);
        if (channel != null) {
            return channel;
        }

        throw new SmsSendException(targetName, "CHANNEL_NOT_FOUND", "短信渠道不存在: " + targetName,
                SmsResultCode.SMS_CHANNEL_NOT_FOUND, "未找到指定的短信渠道配置: " + targetName);
    }

    /**
     * 基于平滑加权轮询 (SWRR) 负载均衡策略发送单条短信
     * 自动跳过处于短时熔断开路态 (OPEN) 的故障渠道
     *
     * @param request 发信请求值对象
     * @return SmsResult 发送结果
     */
    public SmsResult sendByLoadBalance(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        return send(request.withChannel(LOAD_BALANCE_CHANNEL));
    }

    /**
     * 异步基于平滑加权轮询 (SWRR) 负载均衡策略发送单条短信
     *
     * @param request 发信请求值对象
     * @return CompletableFuture 异步结果
     */
    public CompletableFuture<SmsResult> sendByLoadBalanceAsync(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        return CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> sendByLoadBalance(request)), executor);
    }

    /**
     * 查询指定渠道当前已消耗的配额计数
     *
     * @param channelName 渠道名称
     * @return 已消耗配额数量
     */
    public long getChannelQuotaUsed(String channelName) {
        if (quotaGuard == null || !StringUtils.hasText(channelName)) {
            return 0L;
        }
        SmsChannel channel = channelRegistry != null ? channelRegistry.get(channelName.trim()) : null;
        if (channel != null) {
            return quotaGuard.getUsedQuota(channel);
        }
        return quotaGuard.getUsedQuota(channelName.trim(), SmsQuotaResetCycle.DAILY);
    }

    /**
     * 手动重置指定渠道的配额计数
     *
     * @param channelName 渠道名称（为空时重置全部渠道配额）
     */
    public void resetChannelQuota(String channelName) {
        if (quotaGuard == null) {
            return;
        }
        if (StringUtils.hasText(channelName)) {
            quotaGuard.resetQuota(channelName.trim());
        } else {
            quotaGuard.resetAll();
        }
    }

    /**
     * 查询指定渠道当前是否处于短时熔断开路态 (OPEN)
     *
     * @param channelName 渠道名称
     * @return true 处于短时熔断态
     */
    public boolean isChannelOpen(String channelName) {
        return circuitBreaker != null && circuitBreaker.isOpen(channelName);
    }

    /**
     * 手动重置指定渠道的短时熔断状态为 CLOSED
     *
     * @param channelName 渠道名称
     */
    public void resetChannelCircuitBreaker(String channelName) {
        if (circuitBreaker != null) {
            circuitBreaker.reset(channelName);
        }
    }

    /**
     * 手动重置所有渠道的短时熔断状态为 CLOSED
     */
    public void resetAllChannelCircuitBreakers() {
        if (circuitBreaker != null) {
            circuitBreaker.resetAll();
        }
    }

    /**
     * 重置负载均衡器所有渠道的动态当前权重
     */
    public void resetLoadBalancer() {
        if (loadBalancer != null) {
            loadBalancer.reset();
        }
    }

    /**
     * 将指定手机号纳入出站黑名单（使用默认拦截原因）
     *
     * @param phone 手机号
     */
    public void addBlacklist(String phone) {
        if (blacklistProvider != null) {
            blacklistProvider.add(phone);
        }
    }

    /**
     * 将指定手机号及拦截原因纳入出站黑名单
     *
     * @param phone  手机号
     * @param reason 自定义拦截原因
     */
    public void addBlacklist(String phone, String reason) {
        if (blacklistProvider != null) {
            blacklistProvider.add(phone, reason);
        }
    }

    /**
     * 将指定手机号从出站黑名单移除
     *
     * @param phone 手机号
     */
    public void removeBlacklist(String phone) {
        if (blacklistProvider != null) {
            blacklistProvider.remove(phone);
        }
    }

    /**
     * 判断指定手机号是否处于出站黑名单中
     *
     * @param phone 手机号
     * @return true 处于黑名单中
     */
    public boolean isBlacklisted(String phone) {
        return blacklistProvider != null && blacklistProvider.isBlacklisted(phone);
    }

    /**
     * 获取当前黑名单手机号列表快照
     *
     * @return 黑名单手机号集合
     */
    public Set<String> getBlacklist() {
        return blacklistProvider != null ? blacklistProvider.getBlacklist() : Collections.emptySet();
    }

    /**
     * 判断当前系统时刻是否处于夜间免打扰时段
     *
     * @return true 处于免打扰窗口内
     */
    public boolean isInQuietHours() {
        return isInQuietHours(LocalTime.now(clock));
    }

    /**
     * 判断指定时刻是否处于夜间免打扰时段
     * 支持跨零点时段（如 22:00 ~ 08:00）及当日时段（如 12:00 ~ 14:00）精确检测
     *
     * @param time 待判定时刻
     * @return true 处于免打扰窗口内
     */
    public boolean isInQuietHours(LocalTime time) {
        if (time == null || !properties.getGuard().isQuietHoursEnabled()) {
            return false;
        }
        String startStr = properties.getGuard().getQuietHoursStart();
        String endStr = properties.getGuard().getQuietHoursEnd();
        if (!StringUtils.hasText(startStr) || !StringUtils.hasText(endStr)) {
            return false;
        }
        try {
            LocalTime start = LocalTime.parse(startStr.trim());
            LocalTime end = LocalTime.parse(endStr.trim());
            if (start.equals(end)) {
                return false;
            }
            if (start.isAfter(end)) {
                // 跨零点窗口 (如 22:00 ~ 08:00)
                return !time.isBefore(start) || time.isBefore(end);
            } else {
                // 当日闭合窗口 (如 12:00 ~ 14:00)
                return !time.isBefore(start) && time.isBefore(end);
            }
        } catch (Exception ex) {
            log.warn("[SmsTemplate] 解析夜间免打扰时段异常: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 底层逃生舱：获取指定服务商的原生驱动实例
     *
     * @param supplier 服务商类型
     * @return 对应的 SmsProvider 驱动
     */
    public SmsProvider getProvider(SmsSupplier supplier) {
        return providerMap.get(supplier);
    }

    /**
     * 底层逃生舱：按驱动唯一编码（{@code providerCode}）获取原生驱动实例
     *
     * @param providerCode 驱动唯一编码（内置厂商小写名称或自定义扩展驱动标识）
     * @return 对应的 SmsProvider 驱动，不存在时返回 {@code null}
     */
    public SmsProvider getProvider(String providerCode) {
        return failoverChain.getProvider(providerCode);
    }

    /**
     * 提交延迟发送单条短信任务（两阶段校验：提交期仅做参数/模板/渠道 Fail-Fast 校验，不预先扣减预算与配额；到点触发完整实时发送安全链）
     *
     * @param request 发信请求值对象
     * @param delay   延迟等待时长（非负）
     * @return 可取消的延迟调度回执句柄
     */
    public SmsScheduledReceipt sendDelayed(SmsRequest request, Duration delay) {
        BusinessAssert.notNull(delay, "延迟发送时长 delay 不能为空");
        BusinessAssert.isTrue(!delay.isNegative(), "延迟发送时长 delay 不能为负数");
        return sendAt(request, clock.instant().plus(delay));
    }

    /**
     * 提交指定未来/即时触发时刻的定时发送单条短信任务
     *
     * @param request   发信请求值对象
     * @param triggerAt 预定触发时刻
     * @return 可取消的延迟调度回执句柄
     */
    public SmsScheduledReceipt sendAt(SmsRequest request, Instant triggerAt) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.hasText(request.getPhone(), "目标手机号不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        SmsChannel resolvedChannel = resolveChannel(request.getChannel());
        validateAndResolveMode(resolvedChannel, request.getTemplateId(), request.getContent());
        return delayScheduler.scheduleSingle(request, triggerAt, resolvedChannel.getName());
    }

    /**
     * 提交延迟发送批量短信任务（两阶段校验：提交期不做实时黑名单与预算配额扣减，到点走完整批量实时链路）
     *
     * @param request 批量发信请求
     * @param delay   延迟等待时长（非负）
     * @return 可取消的延迟调度回执句柄
     */
    public SmsScheduledReceipt sendBatchDelayed(BatchSmsRequest request, Duration delay) {
        BusinessAssert.notNull(delay, "延迟发送时长 delay 不能为空");
        BusinessAssert.isTrue(!delay.isNegative(), "延迟发送时长 delay 不能为负数");
        return sendBatchAt(request, clock.instant().plus(delay));
    }

    /**
     * 提交指定触发时刻的定时批量短信发送任务
     *
     * @param request   批量发信请求
     * @param triggerAt 预定触发时刻
     * @return 可取消的延迟调度回执句柄
     */
    public SmsScheduledReceipt sendBatchAt(BatchSmsRequest request, Instant triggerAt) {
        BusinessAssert.notNull(request, "批量发信请求 BatchSmsRequest 不能为空");
        BusinessAssert.notEmpty(request.getPhones(), "目标手机号列表不能为空");
        BusinessAssert.notNull(triggerAt, "预定触发时刻 triggerAt 不能为空");
        SmsChannel resolvedChannel = resolveChannel(request.getChannel());
        validateAndResolveMode(resolvedChannel, request.getTemplateId(), request.getContent());
        return delayScheduler.scheduleBatch(request, triggerAt, resolvedChannel.getName());
    }

    /**
     * 根据调度任务 ID 撤销取消尚未执行的延迟或定时短信任务
     *
     * @param taskId 调度任务 ID
     * @return true 表示取消成功，false 表示任务已触发、已完成或不存在
     */
    public boolean cancelScheduled(String taskId) {
        return delayScheduler != null && delayScheduler.cancel(taskId);
    }

    /**
     * 主动查询云厂商单条短信发送状态与送达回执详情
     *
     * @param channelName 渠道名称（为空时走级联默认渠道解析）
     * @param phone       目标手机号
     * @param bizId       业务回执流水号
     * @param sendDate    发送日期（可空，默认当日）
     * @return SmsDeliveryQueryResult 主动查询结果（不支持查询的厂商返回 supported=false 明确契约）
     */
    public SmsDeliveryQueryResult querySendStatus(String channelName, String phone, String bizId, LocalDate sendDate) {
        SmsChannel resolved = resolveChannel(channelName);
        SmsProvider provider = failoverChain.resolveProvider(resolved);
        if (provider == null) {
            throw new SmsSendException(resolved.getName(), "PROVIDER_NOT_FOUND",
                    "未找到云厂商驱动: " + resolved.resolveProviderCode(),
                    SmsResultCode.SMS_CHANNEL_NOT_FOUND,
                    "未找到云厂商驱动: " + resolved.resolveProviderCode());
        }
        return provider.querySendStatus(phone, bizId, sendDate, resolved);
    }

    /**
     * 只读探查指定渠道的云厂商账户余额或剩余短信条数
     *
     * @param channelName 渠道名称（为空时走级联默认渠道解析）
     * @return SmsBalanceResult 余额探查结果（不支持查询的厂商返回 supported=false 明确契约）
     */
    public SmsBalanceResult queryBalance(String channelName) {
        SmsChannel resolved = resolveChannel(channelName);
        SmsProvider provider = failoverChain.resolveProvider(resolved);
        if (provider == null) {
            throw new SmsSendException(resolved.getName(), "PROVIDER_NOT_FOUND",
                    "未找到云厂商驱动: " + resolved.resolveProviderCode(),
                    SmsResultCode.SMS_CHANNEL_NOT_FOUND,
                    "未找到云厂商驱动: " + resolved.resolveProviderCode());
        }
        return provider.queryBalance(resolved);
    }

    private void recordMetricsForOutcome(SmsChannel channel,
                                         String fallbackChannelName,
                                         String blockCategory,
                                         boolean success,
                                         Throwable failure,
                                         long costMillis) {
        if (metricsRecorder == null) {
            return;
        }
        String chName = channel != null ? channel.getName() : fallbackChannelName;
        String supplierCode = channel != null ? channel.resolveProviderCode() : null;
        Duration duration = Duration.ofMillis(Math.max(0L, costMillis));

        if (blockCategory != null) {
            metricsRecorder.recordBlocked(chName, blockCategory);
            metricsRecorder.recordSend(chName, supplierCode, SmsSendOutcome.BLOCKED, duration);
            return;
        }
        if (failure == null) {
            SmsSendOutcome outcome = success ? SmsSendOutcome.SUCCESS : SmsSendOutcome.FAILURE;
            metricsRecorder.recordSend(chName, supplierCode, outcome, duration);
            return;
        }
        if (failure instanceof SmsRateLimitException) {
            metricsRecorder.recordRateLimited("notify", chName);
            metricsRecorder.recordSend(chName, supplierCode, SmsSendOutcome.RATE_LIMITED, duration);
        } else if (failure instanceof SmsIdempotentException) {
            metricsRecorder.recordSend(chName, supplierCode, SmsSendOutcome.IDEMPOTENT_REJECTED, duration);
        } else if (failure instanceof SmsBudgetExceededException budgetEx) {
            SmsSendOutcome outcome = budgetEx.isChannelQuota()
                    ? SmsSendOutcome.QUOTA_EXCEEDED
                    : SmsSendOutcome.BUDGET_EXCEEDED;
            metricsRecorder.recordSend(chName, supplierCode, outcome, duration);
        } else {
            metricsRecorder.recordSend(chName, supplierCode, SmsSendOutcome.FAILURE, duration);
        }
    }

    private String validateAndResolveMode(SmsChannel channel, String templateId, String content) {
        if (StringUtils.hasText(templateId)) {
            return templateId.trim();
        }
        if (StringUtils.hasText(content)) {
            SmsProvider provider = failoverChain.resolveProvider(channel);
            boolean supported = provider != null
                    ? provider.supportsPlainText()
                    : (channel.getSupplier() == null || channel.getSupplier().supportsPlainText());
            if (!supported) {
                String providerId = provider != null ? provider.getProviderCode() : channel.resolveProviderCode();
                throw new SmsException(SmsResultCode.SMS_PARAM_INVALID,
                        "短信驱动 [" + providerId + "] 仅支持模板短信，不支持纯文本 content 直发");
            }
            return null;
        }
        String effectiveTemplateId = channel.resolveTemplateId(templateId);
        BusinessAssert.hasText(effectiveTemplateId,
                () -> "短信模板编码不能为空 (渠道 [" + channel.getName() + "] 未配置 defaultTemplateId 且未指定模板或纯文本内容)");
        return effectiveTemplateId;
    }

    private SmsSupplier resolveEffectiveSupplier(SmsChannel channel) {
        if (channel == null) {
            return null;
        }
        SmsProvider provider = failoverChain.resolveProvider(channel);
        if (provider != null && provider.getSupplier() != null) {
            return provider.getSupplier();
        }
        return channel.getSupplier();
    }

    private void releaseIdempotentOnFailure(boolean batchSuccess, int successCount, boolean lockAcquired, String idempotentKey) {
        if (!batchSuccess && successCount == 0 && lockAcquired && idempotentGuard != null) {
            idempotentGuard.releaseLock(idempotentKey);
        }
    }

    private SmsChannel syncChannelAfterPreSend(SmsSendContext sendContext, SmsChannel currentChannel, String requestChannelName) {
        if (sendContext.getChannel() != null && sendContext.getChannel() != currentChannel) {
            return sendContext.getChannel();
        }
        if (requestChannelName != null && (currentChannel == null || !requestChannelName.equals(currentChannel.getName()))) {
            SmsChannel resolved = resolveChannel(requestChannelName);
            sendContext.setChannel(resolved);
            return resolved;
        }
        return currentChannel;
    }

    private void invokePostSend(int executedCount, SmsSendContext context, SmsResult result) {
        if (sendInterceptors.isEmpty() || context == null || executedCount <= 0) {
            return;
        }
        int bound = Math.min(executedCount, sendInterceptors.size());
        for (int i = bound - 1; i >= 0; i--) {
            SmsSendInterceptor interceptor = sendInterceptors.get(i);
            try {
                interceptor.postSend(context, result);
            } catch (Exception ex) {
                log.warn("[SmsTemplate] 拦截器 [{}] postSend 执行异常: {}", interceptor.getClass().getName(), ex.getMessage());
            }
        }
    }

    private void invokeOnError(int executedCount, SmsSendContext context, Throwable error) {
        if (sendInterceptors.isEmpty() || context == null || executedCount <= 0) {
            return;
        }
        int bound = Math.min(executedCount, sendInterceptors.size());
        for (int i = bound - 1; i >= 0; i--) {
            SmsSendInterceptor interceptor = sendInterceptors.get(i);
            try {
                interceptor.onError(context, error);
            } catch (Exception ex) {
                log.warn("[SmsTemplate] 拦截器 [{}] onError 执行异常: {}", interceptor.getClass().getName(), ex.getMessage());
            }
        }
    }

    private SmsResult representativeBatchResult(BatchSmsResult batchResult, SmsChannel channel) {
        if (batchResult != null && !batchResult.getBatchResults().isEmpty()) {
            return batchResult.getBatchResults().get(0);
        }
        String chName = channel != null ? channel.getName() : "mock";
        SmsSupplier supplier = resolveEffectiveSupplier(channel);
        if (batchResult != null && batchResult.isSuccess()) {
            return SmsResult.success(chName, supplier, null, null, batchResult.getSuccessCount());
        }
        return SmsResult.failure(chName, supplier, "BATCH_FAILED", "Batch send failed or blocked");
    }

    /**
     * 切片执行中间结果记录
     */
    private record SliceExecutionResult(List<String> slicePhones, SmsResult result, Exception exception) {
    }
}
