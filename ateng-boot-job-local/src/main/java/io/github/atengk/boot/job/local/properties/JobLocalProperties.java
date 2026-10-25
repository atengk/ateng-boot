package io.github.atengk.boot.job.local.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 单机定时任务核心配置属性
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.job.local")
public class JobLocalProperties {

    /**
     * 是否启用单机定时任务模块调度引擎（默认 true）
     */
    private boolean enabled = true;

    /**
     * 重启时是否优先尊重数据库中的持久化状态与 Cron 配置（默认 true）
     * <p>
     * 开启后，若任务在数据库/后台被暂停或修改了周期，重启时不会被代码中的静态注解覆盖。
     * </p>
     */
    private boolean preferDatabaseConfig = true;

    /**
     * 调度线程池核心大小（默认根据 CPU 核心数自适应，最小 4，生产高并发按需调优）
     */
    private int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors());

    /**
     * 调度线程名称前缀（默认 ateng-job-local-）
     */
    private String threadNamePrefix = "ateng-job-local-";

    /**
     * 停机时等待运行中任务执行完成的最大优雅超时时长（默认 30s）
     */
    private Duration shutdownAwaitTerminationPeriod = Duration.ofSeconds(30);

    /**
     * 内存模式下每个任务保留的最大执行日志记录条数（默认 100 条，超出自动淘汰最老记录防 OOM）
     */
    private int inMemoryLogCapacity = 100;

    /**
     * 数据库持久化模式下历史执行日志的最大保留天数（默认 7 天，定时任务每日凌晨自动清理过期日志）
     */
    private int logRetentionDays = 7;

    /**
     * 任务失败告警配置
     */
    @NestedConfigurationProperty
    private AlertProperties alert = new AlertProperties();

    /**
     * 告警属性嵌套配置类
     */
    @Getter
    @Setter
    public static class AlertProperties {

        /**
         * 是否启用任务执行失败或超时群告警推送（默认 false）
         */
        private boolean enabled = false;
    }
}
