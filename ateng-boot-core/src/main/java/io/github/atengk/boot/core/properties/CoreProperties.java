package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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
}
