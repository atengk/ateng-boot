package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.properties.SmsChannelProperties;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.properties.SmsTemplateProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多命名短信渠道注册中心
 * 统一纳管 ateng.boot.sms.channels.* 命名的多云、多账号渠道配置
 * 支持多渠道实例的生命周期纳管、缓存寻址、动态热注册与注销
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class SmsChannelRegistry {

    private final Map<String, SmsChannel> channelMap = new ConcurrentHashMap<>();

    public SmsChannelRegistry() {
    }

    public SmsChannelRegistry(SmsProperties properties) {
        if (properties != null && properties.getChannels() != null) {
            initFromProperties(properties.getChannels());
        }
    }

    /**
     * 基于配置属性初始化渠道映射
     *
     * @param channelsConfig 配置属性中的渠道映射
     */
    public void initFromProperties(Map<String, SmsChannelProperties> channelsConfig) {
        if (channelsConfig == null || channelsConfig.isEmpty()) {
            return;
        }
        channelsConfig.forEach((name, prop) -> {
            if (prop != null) {
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
                        .timeout(prop.getTimeout())
                        .defaultTemplateId(prop.getDefaultTemplateId())
                        .statusCallback(prop.getStatusCallback())
                        .weight(prop.getWeight() > 0 ? prop.getWeight() : 1)
                        .maxRetries(prop.getMaxRetries())
                        .retryInterval(prop.getRetryInterval() != null ? prop.getRetryInterval() : java.time.Duration.ofSeconds(1))
                        .templates(prop.getTemplates() != null ? Collections.unmodifiableMap(new LinkedHashMap<>(prop.getTemplates())) : Collections.emptyMap())
                        .templateParamOrders(paramOrders)
                        .build();
                register(channel);
            }
        });
    }

    /**
     * 动态注册或更新短信渠道
     *
     * @param channel 短信渠道配置
     */
    public void register(SmsChannel channel) {
        BusinessAssert.notNull(channel, "注册的短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getName(), "短信渠道名称不能为空");
        BusinessAssert.notNull(channel.getSupplier(), "短信渠道对应的服务商 SmsSupplier 不能为空");

        channelMap.put(channel.getName().trim(), channel);
        if (log.isInfoEnabled()) {
            log.info("[SmsChannelRegistry] 成功注册/更新短信渠道 [名称: {}, 服务商: {}, 容灾备用: {}]",
                    channel.getName(), channel.getSupplier(), channel.getFailoverChannel());
        }
    }

    /**
     * 动态注销指定名称的短信渠道
     *
     * @param channelName 渠道名称
     * @return 被注销的 SmsChannel 对象，若不存在则返回 null
     */
    public SmsChannel unregister(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        SmsChannel removed = channelMap.remove(channelName.trim());
        if (removed != null && log.isInfoEnabled()) {
            log.info("[SmsChannelRegistry] 成功注销短信渠道 [名称: {}]", channelName);
        }
        return removed;
    }

    /**
     * 获取指定名称的渠道配置
     * 若未找到且名称为 "mock"，保底返回内存 Mock 渠道
     *
     * @param channelName 渠道名称
     * @return SmsChannel 渠道对象，未找到返回 null
     */
    public SmsChannel get(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        String name = channelName.trim();
        SmsChannel channel = channelMap.get(name);
        if (channel != null) {
            return channel;
        }
        // 若名称为 mock 且未显式配置，保底构造内存 Mock 渠道
        if ("mock".equalsIgnoreCase(name)) {
            return SmsChannel.builder()
                    .name("mock")
                    .supplier(SmsSupplier.MOCK)
                    .signName("MOCK")
                    .build();
        }
        return null;
    }

    /**
     * 判断是否已注册指定名称的渠道
     *
     * @param channelName 渠道名称
     * @return true 表示已存在
     */
    public boolean contains(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return false;
        }
        String name = channelName.trim();
        return channelMap.containsKey(name) || "mock".equalsIgnoreCase(name);
    }

    /**
     * 获取所有已注册渠道的不可变视图
     *
     * @return 渠道名称 -> SmsChannel 映射
     */
    public Map<String, SmsChannel> getChannels() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(channelMap));
    }

    /**
     * 获取当前已注册渠道数量
     *
     * @return 渠道数量
     */
    public int size() {
        return channelMap.size();
    }

    /**
     * 清空所有渠道（测试隔离清理）
     */
    public void clear() {
        channelMap.clear();
    }
}
