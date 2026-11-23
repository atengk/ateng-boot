package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.exception.SmsBlacklistException;

import java.util.Collection;
import java.util.Set;

/**
 * 手机号出站黑名单前置过滤器 SPI
 * 发信前对已退订用户或涉诈风控手机号实施前置静默拦截，避免向云厂商产生非必要调用
 * 对称提供增、删、查、清空等完整生命周期治理契约
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsBlacklistProvider {

    /**
     * 判断指定手机号是否处于黑名单中
     *
     * @param phone 待检测手机号（真明文）
     * @return true 表示处于黑名单中需静默拦截；false 表示放行
     */
    boolean isBlacklisted(String phone);

    /**
     * 获取黑名单拦截原因说明
     *
     * @param phone 手机号
     * @return 拦截原因说明
     */
    default String getReason(String phone) {
        return "目标手机号已退订或处于出站安全风控黑名单";
    }

    /**
     * 将指定手机号加入黑名单（使用默认拦截原因）
     *
     * @param phone 目标手机号
     */
    void add(String phone);

    /**
     * 将指定手机号及拉黑原因加入黑名单
     *
     * @param phone  目标手机号
     * @param reason 自定义拦截原因
     */
    void add(String phone, String reason);

    /**
     * 批量将手机号加入黑名单
     *
     * @param phones 目标手机号集合
     */
    void addAll(Collection<String> phones);

    /**
     * 将指定手机号从黑名单移除
     *
     * @param phone 目标手机号
     */
    void remove(String phone);

    /**
     * 批量将手机号从黑名单移除
     *
     * @param phones 目标手机号集合
     */
    void removeAll(Collection<String> phones);

    /**
     * 获取所有黑名单手机号集合快照
     *
     * @return 黑名单手机号集合
     */
    Set<String> getBlacklist();

    /**
     * 清空所有黑名单数据
     */
    void clear();

    /**
     * 获取当前黑名单总数
     *
     * @return 黑名单手机号数量
     */
    int size();

    /**
     * 编程式显式防卫校验
     * 若处于黑名单中则立即抛出 {@link SmsBlacklistException}
     *
     * @param phone 手机号
     * @throws SmsBlacklistException 当手机号处于黑名单时抛出
     */
    default void validateNotBlacklisted(String phone) {
        if (isBlacklisted(phone)) {
            throw new SmsBlacklistException(phone, getReason(phone));
        }
    }
}
