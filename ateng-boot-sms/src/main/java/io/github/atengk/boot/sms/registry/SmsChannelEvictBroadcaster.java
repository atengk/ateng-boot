package io.github.atengk.boot.sms.registry;

/**
 * 短信渠道热驱逐集群广播 SPI（解耦可选 Redis 依赖防 NoClassDefFoundError）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@FunctionalInterface
public interface SmsChannelEvictBroadcaster {

    /**
     * 向集群其他节点广播渠道驱逐指令
     *
     * @param channelName 待驱逐渠道名称，null 表示全量驱逐
     */
    void publishEvict(String channelName);
}
