package io.github.atengk.boot.web.ratelimit.store;

import java.util.concurrent.TimeUnit;

/**
 * 接口限流状态存储与许可获取 SPI 契约接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface RateLimiterStore {

    /**
     * 尝试在指定时间窗口内获取许可（令牌）
     *
     * @param key   限流业务唯一标识键
     * @param count 时间周期内允许的最大请求数（令牌容量）
     * @param time  时间窗口大小
     * @param unit  时间单位
     * @return true 获取许可成功（未超限）；false 超过阈值（触发限流）
     */
    boolean tryAcquire(String key, long count, long time, TimeUnit unit);
}
