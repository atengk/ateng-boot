package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.context.SmsContextHolder;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.failover.SmsFailoverChain;
import io.github.atengk.boot.sms.guard.DailyBudgetBreaker;
import io.github.atengk.boot.sms.guard.InMemoryDailyBudgetBreaker;
import io.github.atengk.boot.sms.guard.InMemorySmsBlacklistProvider;
import io.github.atengk.boot.sms.guard.SmsBlacklistProvider;
import io.github.atengk.boot.sms.model.*;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.registry.SmsChannelResolver;
import io.github.atengk.boot.sms.registry.TenantSmsChannelResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 短信统一操作核心门面
 * 对外暴露高内聚、易于使用的发信 API（单发、群发透明切片、异步投递），屏蔽底层异构云厂商网络与鉴权细节
 * 集成三级级联寻址引擎（SmsContextHolder -> SmsChannelResolver -> default-channel）、多命名渠道注册中心、自动顺延容灾链、黑名单前置静默防卫与单日成本预算硬熔断器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsTemplate {

    @Getter
    private final SmsProperties properties;
    private final Map<SmsSupplier, SmsProvider> providerMap = new ConcurrentHashMap<>();

    @Getter
    private final SmsChannelRegistry channelRegistry;

    @Getter
    private final List<SmsChannelResolver> channelResolvers;

    @Getter
    private final SmsFailoverChain failoverChain;

    @Getter
    private final SmsBlacklistProvider blacklistProvider;

    @Getter
    private final DailyBudgetBreaker dailyBudgetBreaker;

    @Getter
    private final Executor executor;

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsChannelRegistry channelRegistry,
                       List<SmsChannelResolver> channelResolvers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       Executor executor) {
        this.properties = properties != null ? properties : new SmsProperties();
        if (providers != null) {
            for (SmsProvider provider : providers) {
                if (provider != null && provider.getSupplier() != null) {
                    this.providerMap.put(provider.getSupplier(), provider);
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

        this.failoverChain = new SmsFailoverChain(this.channelRegistry, this.providerMap);
        this.blacklistProvider = blacklistProvider != null ? blacklistProvider : new InMemorySmsBlacklistProvider();
        this.dailyBudgetBreaker = dailyBudgetBreaker != null ? dailyBudgetBreaker : new InMemoryDailyBudgetBreaker(this.properties.getGuard());
        this.executor = executor != null ? executor : AsyncUtil.getExecutor();
    }

    public SmsTemplate(SmsProperties properties,
                       List<SmsProvider> providers,
                       SmsBlacklistProvider blacklistProvider,
                       DailyBudgetBreaker dailyBudgetBreaker,
                       Executor executor) {
        this(properties, providers, null, null, blacklistProvider, dailyBudgetBreaker, executor);
    }

    public SmsTemplate(SmsProperties properties, List<SmsProvider> providers) {
        this(properties, providers, null, null, null, null, null);
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
        BusinessAssert.hasText(request.getTemplateId(), "短信模板编码不能为空");

        // 1. 三级级联解析目标发信渠道
        SmsChannel channel = resolveChannel(request.getChannel());

        // 2. 出站黑名单前置静默拦截防卫
        if (properties.getGuard().isBlacklistEnabled() && blacklistProvider != null && blacklistProvider.isBlacklisted(request.getPhone())) {
            String reason = blacklistProvider.getReason(request.getPhone());
            if (log.isWarnEnabled()) {
                log.warn("[SmsTemplate] 目标手机号 [{}] 命中出站黑名单，前置静默拦截 (原因: {})",
                        DesensitizeUtil.mobilePhone(request.getPhone()), reason);
            }
            return SmsResult.blocked(channel.getName(), reason);
        }

        // 3. 单日成本预算硬熔断防卫
        if (dailyBudgetBreaker != null) {
            dailyBudgetBreaker.checkAndIncrement(request.getPhones().size());
        }

        // 4. 经由高可用顺延容灾链执行物理发信
        if (log.isDebugEnabled()) {
            log.debug("[SmsTemplate] 准备发送短信 -> 目标号码: {}, 初始渠道: {}, 服务商: {}, 模板: {}",
                    DesensitizeUtil.mobilePhone(request.getPhone()),
                    channel.getName(),
                    channel.getSupplier(),
                    request.getTemplateId());
        }

        return failoverChain.execute(request, channel);
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
        BusinessAssert.hasText(request.getTemplateId(), "短信模板编码不能为空");

        // 1. 三级级联解析目标渠道
        SmsChannel channel = resolveChannel(request.getChannel());

        // 2. 出站黑名单前置静默拦截
        List<String> rawPhones = request.getPhones();
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

        // 若全部号码均处于黑名单中，直接阻断外发并返回拦截结果
        if (allowedPhones.isEmpty()) {
            return BatchSmsResult.of(
                    rawPhones.size(),
                    0,
                    Collections.emptyList(),
                    blacklistedPhones,
                    List.of(SmsResult.blocked(channel.getName(), "全部目标手机号均处于出站黑名单中"))
            );
        }

        // 3. 单日成本预算硬熔断防卫（基于实际拟放行发信量）
        if (dailyBudgetBreaker != null) {
            dailyBudgetBreaker.checkAndIncrement(allowedPhones.size());
        }

        // 4. 依据厂商上限使用 BatchSliceUtil 透明切片
        int sliceLimit = resolveBatchLimit(channel.getSupplier());
        List<List<String>> slices = BatchSliceUtil.slice(allowedPhones, sliceLimit);

        if (log.isDebugEnabled()) {
            log.debug("[SmsTemplate] 批量短信分片投递 -> 渠道: {}, 厂商: {}, 原始号码数: {}, 放行数: {}, 黑名单拦截: {}, 切片数: {}, 单批上限: {}",
                    channel.getName(), channel.getSupplier(), rawPhones.size(), allowedPhones.size(),
                    blacklistedPhones.size(), slices.size(), sliceLimit);
        }

        // 5. 通过 Core 受管异步线程池设施受控并发调度并在各切片批次内部支持顺延容灾
        List<CompletableFuture<SliceExecutionResult>> futures = new ArrayList<>(slices.size());
        for (List<String> slice : slices) {
            SmsRequest sliceRequest = SmsRequest.builder()
                    .phones(slice)
                    .templateId(request.getTemplateId())
                    .params(request.getParams())
                    .signName(request.getSignName())
                    .channel(channel.getName())
                    .bizId(request.getBizId())
                    .build();

            Supplier<SliceExecutionResult> sliceTask = () -> {
                try {
                    SmsResult res = failoverChain.execute(sliceRequest, channel);
                    return new SliceExecutionResult(slice, res, null);
                } catch (Exception ex) {
                    log.error("[SmsTemplate] 批次切片投递异常 -> 批次号码数: {}, 错误: {}", slice.size(), ex.getMessage());
                    return new SliceExecutionResult(slice, null, ex);
                }
            };

            futures.add(CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(sliceTask), executor));
        }

        // 等待所有切片批次执行完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 6. 聚合统计结果
        int successCount = 0;
        List<String> failedPhones = new ArrayList<>();
        List<SmsResult> batchResults = new ArrayList<>(slices.size());

        for (CompletableFuture<SliceExecutionResult> future : futures) {
            SliceExecutionResult execRes = future.join();
            if (execRes.exception != null) {
                failedPhones.addAll(execRes.slicePhones);
                String rawCode = execRes.exception instanceof SmsSendException sse ? sse.getRawCode() : "BATCH_SEND_ERROR";
                batchResults.add(SmsResult.failure(channel.getName(), channel.getSupplier(), rawCode, execRes.exception.getMessage()));
            } else if (execRes.result != null && execRes.result.isSuccess()) {
                successCount += execRes.slicePhones.size();
                batchResults.add(execRes.result);
            } else {
                failedPhones.addAll(execRes.slicePhones);
                batchResults.add(execRes.result != null ? execRes.result : SmsResult.failure(channel.getName(), channel.getSupplier(), "UNKNOWN", "Unknown error"));
            }
        }

        return BatchSmsResult.of(
                rawPhones.size(),
                successCount,
                failedPhones,
                blacklistedPhones,
                batchResults
        );
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
     * 根据云厂商类型解析单批最大支持的手机号切片上限
     * 腾讯云 200，华为云 500，阿里云 1000，其他默认 1000
     *
     * @param supplier 云厂商类型
     * @return 单批最大手机号数量
     */
    public int resolveBatchLimit(SmsSupplier supplier) {
        if (supplier == null) {
            return 1000;
        }
        return switch (supplier) {
            case TENCENT -> 200;
            case HUAWEI -> 500;
            case ALIYUN, MOCK -> 1000;
            default -> 1000;
        };
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

        SmsChannel channel = channelRegistry.get(targetName);
        if (channel != null) {
            return channel;
        }

        log.error("[SmsTemplate] 指定或级联寻址的短信渠道 [{}] 不存在，请检查 ateng.boot.sms.channels 配置", targetName);
        throw new SmsSendException(targetName, "CHANNEL_NOT_FOUND", "短信渠道不存在: " + targetName,
                SmsResultCode.SMS_CHANNEL_NOT_FOUND, "未找到指定的短信渠道配置: " + targetName);
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
     * 切片执行中间结果记录
     */
    private record SliceExecutionResult(List<String> slicePhones, SmsResult result, Exception exception) {
    }
}
