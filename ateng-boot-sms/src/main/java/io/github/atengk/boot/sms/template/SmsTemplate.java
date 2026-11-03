package io.github.atengk.boot.sms.template;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
import io.github.atengk.boot.sms.provider.SmsProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信统一操作核心门面
 * 对外暴露高内聚、易于使用的发信 API，屏蔽底层异构云厂商网络与鉴权细节
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsTemplate {

    private final SmsProperties properties;
    private final Map<SmsSupplier, SmsProvider> providerMap = new ConcurrentHashMap<>();
    private final Map<String, SmsChannel> channelCache = new ConcurrentHashMap<>();

    public SmsTemplate(SmsProperties properties, List<SmsProvider> providers) {
        this.properties = properties != null ? properties : new SmsProperties();
        if (providers != null) {
            for (SmsProvider provider : providers) {
                if (provider != null && provider.getSupplier() != null) {
                    this.providerMap.put(provider.getSupplier(), provider);
                }
            }
        }
        initChannels();
    }

    /**
     * 初始化配置中的多命名渠道定义
     */
    private void initChannels() {
        channelCache.clear();
        Map<String, SmsChannelProperties> channelsConfig = properties.getChannels();
        if (channelsConfig != null) {
            channelsConfig.forEach((name, prop) -> {
                Map<String, List<String>> paramOrders = new HashMap<>();
                if (prop.getTemplates() != null) {
                    for (Map.Entry<String, SmsTemplateProperties> entry : prop.getTemplates().entrySet()) {
                        if (entry.getValue() != null && entry.getValue().getParamOrder() != null) {
                            paramOrders.put(entry.getKey(), entry.getValue().getParamOrder());
                        }
                    }
                }
                SmsChannel channel = SmsChannel.builder()
                        .name(name)
                        .supplier(prop.getSupplier())
                        .accessKeyId(prop.getAccessKeyId())
                        .accessKeySecret(prop.getAccessKeySecret())
                        .signName(prop.getSignName())
                        .appId(prop.getAppId())
                        .region(prop.getRegion())
                        .endpoint(prop.getEndpoint())
                        .failoverChannel(prop.getFailoverChannel())
                        .templateParamOrders(paramOrders)
                        .build();
                channelCache.put(name, channel);
            });
        }
    }

    /**
     * 发送单条短信
     *
     * @param request 发信请求值对象
     * @return SmsResult 发送结果
     * @throws SmsSendException 当发信失败或渠道不存在时抛出
     */
    public SmsResult send(SmsRequest request) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.hasText(request.getPhone(), "目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "短信模板编码不能为空");

        // 1. 解析目标发信渠道
        SmsChannel channel = resolveChannel(request.getChannel());

        // 2. 匹配对应云厂商的驱动实现
        SmsProvider provider = providerMap.get(channel.getSupplier());
        if (provider == null) {
            log.error("[SmsTemplate] 未找到云厂商 [{}] 的驱动实现 (渠道: {})", channel.getSupplier(), channel.getName());
            throw new SmsSendException(channel.getName(), "PROVIDER_NOT_FOUND", "未找到对应的短信服务商驱动实现",
                    SmsResultCode.SMS_CHANNEL_NOT_FOUND, "未找到云厂商驱动: " + channel.getSupplier());
        }

        // 3. 执行物理调用
        if (log.isDebugEnabled()) {
            log.debug("[SmsTemplate] 准备发送短信 -> 目标号码: {}, 渠道: {}, 服务商: {}, 模板: {}",
                    DesensitizeUtil.mobilePhone(request.getPhone()),
                    channel.getName(),
                    channel.getSupplier(),
                    request.getTemplateId());
        }

        return provider.send(request, channel);
    }

    /**
     * 根据渠道名称解析短信渠道对象
     *
     * @param channelName 渠道名称（可为 null，为 null 时使用默认渠道）
     * @return SmsChannel 渠道对象
     */
    public SmsChannel resolveChannel(String channelName) {
        String targetName = channelName;
        if (targetName == null || targetName.isBlank()) {
            targetName = properties.getDefaultChannel();
        }
        if (targetName == null || targetName.isBlank()) {
            targetName = "mock";
        }

        // 优先从已配置渠道缓存获取
        SmsChannel channel = channelCache.get(targetName);
        if (channel != null) {
            return channel;
        }

        // 若默认配置为 mock 且未显式在 properties.channels 配置 mock 节点，保底构造内存 Mock 渠道
        if ("mock".equalsIgnoreCase(targetName)) {
            return SmsChannel.builder()
                    .name("mock")
                    .supplier(SmsSupplier.MOCK)
                    .signName("MOCK")
                    .build();
        }

        log.error("[SmsTemplate] 指定的短信渠道 [{}] 不存在，请检查 ateng.boot.sms.channels 配置", targetName);
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
}
