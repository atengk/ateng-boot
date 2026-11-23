package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsQuotaResetCycle;

/**
 * 单短信渠道周期/累计配额防卫守卫 SPI
 * <p>
 * 用于控制单个短信渠道（如预付费短信包、通道配额）的发信总量上限，支持按自然日自动清零 ({@link SmsQuotaResetCycle#DAILY})
 * 与总量包干 ({@link SmsQuotaResetCycle#TOTAL}) 双模式，与负载均衡器与容灾链深度联动实现超限自动切换。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface SmsQuotaGuard {

    /**
     * 原子检查并预扣减指定渠道的发送配额
     *
     * @param channel 目标短信渠道（若 {@code maxQuota <= 0} 则不受限制直接放行）
     * @param count   本次拟发送条数
     * @return true 表示配额充足且已原子扣减；false 表示已达上限应跳过或阻断
     */
    boolean tryAcquire(SmsChannel channel, int count);

    /**
     * 对称补偿释放已预扣减但实际投递失败（并准备容灾切换）的渠道配额
     *
     * @param channel 目标短信渠道
     * @param count   待退还条数
     */
    default void release(SmsChannel channel, int count) {
    }

    /**
     * 只读探测指定渠道当前是否仍有可用配额（不扣减计数，供负载均衡选路过滤）
     *
     * @param channel 目标短信渠道
     * @return true 表示配额可用（或未设上限），false 表示已触达上限
     */
    boolean isQuotaAvailable(SmsChannel channel);

    /**
     * 查询指定渠道当前已消耗的配额计数
     *
     * @param channel 目标短信渠道
     * @return 已使用配额计数
     */
    default long getUsedQuota(SmsChannel channel) {
        if (channel == null || channel.getName() == null) {
            return 0L;
        }
        return getUsedQuota(channel.getName(), channel.getQuotaResetCycle());
    }

    /**
     * 按渠道名与周期模式查询当前已消耗的配额计数
     *
     * @param channelName 渠道名称
     * @param cycle       配额重置周期（为 null 时按 DAILY 处理）
     * @return 已使用配额计数
     */
    long getUsedQuota(String channelName, SmsQuotaResetCycle cycle);

    /**
     * 手动重置指定渠道的配额计数（同时清零当日计数与包干累计计数）
     *
     * @param channelName 渠道名称
     */
    void resetQuota(String channelName);

    /**
     * 清空所有渠道配额计数
     */
    void resetAll();
}
