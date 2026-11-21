package io.github.atengk.boot.sms.registry;

import io.github.atengk.boot.sms.model.SmsChannel;

/**
 * 动态短信渠道按需供给 SPI
 * <p>
 * 用于支持千级多租户或运行期外部数据源（数据库、Nacos 配置中心等）按渠道名按需懒加载完整 {@link SmsChannel} 模型。
 * 加载后的渠道受 {@link SmsChannelRegistry} 有界 LRU + TTL 缓存纳管，并在密钥轮换后通过驱逐事件热失效。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@FunctionalInterface
public interface SmsChannelProvider {

    /**
     * 根据渠道唯一标识加载对应的短信渠道配置
     *
     * @param channelName 渠道名称
     * @return 短信渠道配置对象，未找到时返回 null
     */
    SmsChannel loadChannel(String channelName);
}
