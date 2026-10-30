package io.github.atengk.boot.core.properties;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * 核心系统基线与环境防卫配置属性
 * 提供操作系统时区校准、UTF-8 编码防卫、JVM 网络超时防死锁及慢 Bean 诊断门限等强类型配置
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
@Setter
public class SystemProperties {

    /**
     * 系统增强与环境防卫总开关（默认 true，为 false 时彻底熔断所有系统级干预）
     */
    private boolean enabled = true;

    /**
     * 默认校准时区标识（默认 Asia/Shanghai，支持 UTC 等合法 ZoneId）
     */
    private String timezone = "Asia/Shanghai";

    /**
     * 是否开启时区、UTF-8 字符集与 JVM 底层网络超时防卫（默认 true）
     */
    private boolean timezoneGuardEnabled = true;

    /**
     * 慢 Bean 初始化诊断耗时阈值（默认 500ms，低于此阈值不告警，null 或 0 表示禁用）
     */
    private Duration slowBeanThreshold = Duration.ofMillis(500);

    /**
     * 应用正常关闭时是否输出存活 Uptime 摘要（默认 true）
     */
    private boolean shutdownUptimeEnabled = true;
}
