package io.github.atengk.boot.core.properties;

import io.github.atengk.boot.core.id.Snowflake;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * ateng-boot-core 核心通用底座配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.core")
public class CoreProperties {

    /**
     * 时钟回拨容忍阈值（顶层快捷配置，默认 5ms）
     *
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.snowflake.clock-backoff-tolerance}
     */
    @Deprecated(since = "1.0.0")
    private Duration clockBackoffTolerance = Duration.ofMillis(Snowflake.DEFAULT_MAX_BACKWARD_MS);

    /**
     * 进程内内存缓存默认存活时间 TTL（顶层快捷配置，默认 30 分钟）
     *
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.cache.default-ttl}
     */
    @Deprecated(since = "1.0.0")
    private Duration cacheTtl = Duration.ofMinutes(30);

    /**
     * Snowflake 雪花发号器专属配置
     */
    @NestedConfigurationProperty
    private SnowflakeProperties snowflake = new SnowflakeProperties();

    /**
     * 进程内内存缓存配置
     */
    @NestedConfigurationProperty
    private CacheProperties cache = new CacheProperties();

    /**
     * 系统基线与环境防卫配置
     */
    @NestedConfigurationProperty
    private SystemProperties system = new SystemProperties();

    /**
     * 日志引擎与文件持久化配置
     */
    @NestedConfigurationProperty
    private LoggingProperties logging = new LoggingProperties();

    /**
     * 方法级慢调用告警配置
     */
    @NestedConfigurationProperty
    private SlowAlertProperties slowAlert = new SlowAlertProperties();

    /**
     * 网络通信与网卡探测配置
     */
    @NestedConfigurationProperty
    private NetProperties net = new NetProperties();

    /**
     * 获取时钟回拨容忍阈值（顶层快捷配置）
     *
     * @return 时钟回拨容忍阈值
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.snowflake.clock-backoff-tolerance}
     */
    @Deprecated(since = "1.0.0")
    public Duration getClockBackoffTolerance() {
        return clockBackoffTolerance;
    }

    /**
     * 设置时钟回拨容忍阈值（顶层快捷配置）
     *
     * @param clockBackoffTolerance 时钟回拨容忍阈值
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.snowflake.clock-backoff-tolerance}
     */
    @Deprecated(since = "1.0.0")
    public void setClockBackoffTolerance(Duration clockBackoffTolerance) {
        this.clockBackoffTolerance = clockBackoffTolerance;
    }

    /**
     * 获取进程内内存缓存默认存活时间 TTL（顶层快捷配置）
     *
     * @return 缓存默认存活时间 TTL
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.cache.default-ttl}
     */
    @Deprecated(since = "1.0.0")
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    /**
     * 设置进程内内存缓存默认存活时间 TTL（顶层快捷配置）
     *
     * @param cacheTtl 缓存默认存活时间 TTL
     * @deprecated 自 1.0.0 起不推荐使用，请迁移至 {@code ateng.boot.core.cache.default-ttl}
     */
    @Deprecated(since = "1.0.0")
    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    /**
     * 获取有效的时钟回拨容忍时长
     * 优先采用 snowflake.clockBackoffTolerance，若未指定则回退到顶层 clockBackoffTolerance
     *
     * @return 有效时钟回拨容忍时长
     */
    public Duration getEffectiveClockBackoffTolerance() {
        if (snowflake != null && snowflake.getClockBackoffTolerance() != null) {
            return snowflake.getClockBackoffTolerance();
        }
        return clockBackoffTolerance != null ? clockBackoffTolerance : Duration.ofMillis(Snowflake.DEFAULT_MAX_BACKWARD_MS);
    }

    /**
     * 获取有效的内存缓存存活时间 TTL
     * 优先采用 cache.defaultTtl，若未指定则回退到顶层 cacheTtl
     *
     * @return 有效缓存 TTL
     */
    public Duration getEffectiveCacheTtl() {
        if (cache != null && cache.getDefaultTtl() != null) {
            return cache.getDefaultTtl();
        }
        return cacheTtl != null ? cacheTtl : Duration.ofMinutes(30);
    }
}
