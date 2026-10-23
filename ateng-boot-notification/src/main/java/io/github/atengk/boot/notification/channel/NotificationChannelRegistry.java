package io.github.atengk.boot.notification.channel;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.notification.event.NotificationChannelEvictEvent;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协同通知多命名渠道注册中心与运行时缓存容器
 * 提供并发安全的渠道注册、动态解析、懒加载供给与驱逐能力
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class NotificationChannelRegistry {

    public static final String DEFAULT_CHANNEL_KEY = "default";

    private final ConcurrentMap<String, NotificationChannel> channels = new ConcurrentHashMap<>();
    private final List<NotificationChannelProvider> channelProviders = new CopyOnWriteArrayList<>();
    private final List<NotificationChannelResolver> channelResolvers = new CopyOnWriteArrayList<>();

    @Setter
    private ApplicationEventPublisher eventPublisher;

    /**
     * 默认渠道标识名称
     */
    @Getter
    @Setter
    private String defaultChannelName;

    public NotificationChannelRegistry() {
    }

    public NotificationChannelRegistry(String defaultChannelName) {
        this.defaultChannelName = defaultChannelName;
    }

    /**
     * 注册新的运行时渠道配置
     *
     * @param channel 渠道配置对象
     */
    public void register(NotificationChannel channel) {
        BusinessAssert.notNull(channel, "待注册的渠道配置不能为 null");
        channels.put(channel.getName(), channel);
        if (log.isDebugEnabled()) {
            log.debug("Registered notification channel: name={}, platform={}, webhook={}",
                    channel.getName(), channel.getPlatform(), NotificationDesensitizeUtil.maskWebhook(channel.getWebhook()));
        }
    }

    /**
     * 驱逐注销指定渠道配置
     *
     * @param channelName 待注销的渠道标识名称
     * @return 被移除的渠道配置对象；若原本不存在则返回 null
     */
    public NotificationChannel unregister(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        NotificationChannel removed = channels.remove(channelName.trim());
        if (removed != null) {
            if (log.isDebugEnabled()) {
                log.debug("Unregistered notification channel: name={}", channelName);
            }
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new NotificationChannelEvictEvent(this, channelName.trim(), removed));
            }
        }
        return removed;
    }

    /**
     * 获取指定渠道配置
     * 若未显式传入渠道名称或传入 "default"，则依次通过动态解析器 SPI 推导与默认渠道回退；
     * 若缓存未命中，则尝试通过动态供给器 SPI 懒加载并自动预热缓存
     *
     * @param channelName 渠道标识名称（可为 null 或 "default"）
     * @return 匹配的渠道运行时配置
     * @throws NotificationException 若渠道不存在或未配置
     */
    public NotificationChannel get(String channelName) {
        String targetName = channelName != null ? channelName.trim() : null;

        // 1. 若入参为空或为 "default"，先通过解析器 SPI 动态推导，否则回退默认渠道
        if (targetName == null || targetName.isEmpty() || DEFAULT_CHANNEL_KEY.equalsIgnoreCase(targetName)) {
            targetName = resolveDynamicChannel();
            if (targetName == null || targetName.isEmpty() || DEFAULT_CHANNEL_KEY.equalsIgnoreCase(targetName)) {
                targetName = this.defaultChannelName;
            }
        }

        if (targetName == null || targetName.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                    "未配置默认通知渠道且未显式指定目标渠道");
        }

        // 2. 优先检索内存缓存
        NotificationChannel channel = channels.get(targetName);
        if (channel != null) {
            return channel;
        }

        // 3. 内存未命中时，尝试通过 NotificationChannelProvider SPI 懒加载
        for (NotificationChannelProvider provider : channelProviders) {
            try {
                NotificationChannel loaded = provider.loadChannel(targetName);
                if (loaded != null) {
                    register(loaded);
                    return loaded;
                }
            } catch (Exception ex) {
                log.warn("Failed to load notification channel [{}] from provider [{}]: {}",
                        targetName, provider.getClass().getName(), ex.getMessage(), ex);
            }
        }

        throw new NotificationException(NotificationResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                "未找到名为 [" + targetName + "] 的通知渠道配置");
    }

    /**
     * 检查是否存在指定的通知渠道配置（仅检查当前内存缓存）
     *
     * @param channelName 渠道名称
     * @return true 若已注册在当前缓存容器中
     */
    public boolean has(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return false;
        }
        return channels.containsKey(channelName.trim());
    }

    /**
     * 获取所有当前已注册的渠道不可变只读视图
     *
     * @return 渠道配置不可变映射表
     */
    public Map<String, NotificationChannel> getAll() {
        return Collections.unmodifiableMap(channels);
    }

    /**
     * 清空当前所有缓存的渠道配置
     */
    public void clear() {
        channels.clear();
    }

    /**
     * 当前已缓存的渠道数量
     *
     * @return 渠道总数
     */
    public int size() {
        return channels.size();
    }

    /**
     * 添加外部渠道动态供给器
     *
     * @param provider 渠道供给器 SPI
     */
    public void addProvider(NotificationChannelProvider provider) {
        if (provider != null) {
            channelProviders.add(provider);
        }
    }

    /**
     * 移除指定的外部渠道动态供给器
     *
     * @param provider 渠道供给器 SPI
     */
    public void removeProvider(NotificationChannelProvider provider) {
        if (provider != null) {
            channelProviders.remove(provider);
        }
    }

    /**
     * 添加动态渠道路由解析器
     *
     * @param resolver 路由解析器 SPI
     */
    public void addResolver(NotificationChannelResolver resolver) {
        if (resolver != null) {
            channelResolvers.add(resolver);
        }
    }

    /**
     * 移除指定的动态渠道路由解析器
     *
     * @param resolver 路由解析器 SPI
     */
    public void removeResolver(NotificationChannelResolver resolver) {
        if (resolver != null) {
            channelResolvers.remove(resolver);
        }
    }

    /**
     * 执行动态渠道路由解析（遍历所有挂载的 NotificationChannelResolver SPI）
     *
     * @return 解析到的有效渠道名称，若无解析器匹配则返回 null
     */
    public String resolveDynamicChannel() {
        for (NotificationChannelResolver resolver : channelResolvers) {
            try {
                String resolved = resolver.resolveChannel();
                if (resolved != null && !resolved.isBlank()) {
                    return resolved.trim();
                }
            } catch (Exception ex) {
                log.warn("Failed to resolve channel from resolver [{}]: {}",
                        resolver.getClass().getName(), ex.getMessage(), ex);
            }
        }
        return null;
    }
}
