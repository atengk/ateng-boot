package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * 进程内内存缓存默认配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@Setter
public class CacheProperties {

    /**
     * 进程内内存 LRU 缓存默认最大容量上限（默认 1024）
     */
    private int defaultMaxCapacity = 1024;

    /**
     * 进程内内存 LRU 缓存默认存活时间 TTL（未配置时沿用顶层 cacheTtl，默认 30 分钟）
     */
    private Duration defaultTtl;
}
