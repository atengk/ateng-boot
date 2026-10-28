package io.github.atengk.boot.datasource.readwrite;

import java.time.Duration;

/**
 * 写后读主防延迟写操作感知解析器 SPI
 * 用于支持跨 HTTP 请求、多端异步调用的时间窗口模式 (WINDOW Scope)
 *
 * @author Ateng
 * @since 2026-10-24
 */
public interface WriteOccurredResolver {

    /**
     * 标记当前客户端/用户发生了一次写操作
     *
     * @param windowTtl 时间窗口时长
     */
    void markWrite(Duration windowTtl);

    /**
     * 判断当前客户端/用户在指定时间窗口内是否发生过写操作
     *
     * @param windowTtl 时间窗口时长
     * @return true 表示在窗口内发生过写操作，必须路由主库；false 表示未发生或窗口已过期
     */
    boolean isWriteOccurred(Duration windowTtl);

    /**
     * 清除当前客户端/用户的写操作标记
     */
    default void clear() {
    }
}
