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
     * Actuator 运维端点配置
     */
    @NestedConfigurationProperty
    private ActuatorProperties actuator = new ActuatorProperties();

    /**
     * 任务失败告警配置
     */
    @NestedConfigurationProperty
    private AlertProperties alert = new AlertProperties();

    /**
     * 集群互斥锁配置
     */
    @NestedConfigurationProperty
    private ClusterLockProperties clusterLock = new ClusterLockProperties();

    /**
     * Micrometer 监控指标度量配置
     */
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * Actuator 端点嵌套配置类
     */
    @Getter
    @Setter
    public static class ActuatorProperties {

        /**
         * 是否启用 /actuator/localjobs 运维管控端点（默认 true）
         */
        private boolean enabled = true;
    }

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

        /**
         * 协同通知通道名称（可选，缺省由协同通知组件选用默认通道）
         */
        private String channel;

        /**
         * 三阶防风暴告警聚合器配置
         */
        @NestedConfigurationProperty
        private AntiStormProperties antiStorm = new AntiStormProperties();
    }

    /**
     * 三阶防风暴告警聚合配置类
     */
    @Getter
    @Setter
    public static class AntiStormProperties {

        /**
         * 是否启用告警防风暴聚合器（默认 true，当 alert.enabled=true 时生效，防止高频失败打爆机器人）
         */
        private boolean enabled = true;

        /**
         * 告警抑制滑动静默窗口时长（默认 5m，静默期间抑制重复告警并累计错误计数）
         */
        private Duration silenceWindow = Duration.ofMinutes(5);

        /**
         * 任务自愈成功后是否发送恢复通知（默认 true）
         */
        private boolean recoveryNotice = true;
    }

    /**
     * 集群互斥锁嵌套配置类
     */
    @Getter
    @Setter
    public static class ClusterLockProperties {

        /**
         * 是否启用集群互斥锁（默认 true）
         */
        private boolean enabled = true;

        /**
         * 集群锁存储类型（AUTO: 自动探测 Redis，有 Redis 用 Redis，无 Redis 自动降级为 local 单机内存；REDIS: 强制 Redis；LOCAL: 强制单机内存）
         */
        private String type = "AUTO";

        /**
         * 集群互斥锁在 Redis 中的键名前缀（默认 ateng:job:lock:）
         */
        private String keyPrefix = "ateng:job:lock:";

        /**
         * 默认互斥锁防死锁租约超时时长（默认 5m，当任务未显式指定或指定 0 时生效）
         */
        private Duration defaultLeaseTime = Duration.ofMinutes(5);
    }

    /**
     * Micrometer 监控指标度量配置类
     */
    @Getter
    @Setter
    public static class MetricsProperties {

        /**
         * 是否启用 Micrometer 监控指标采集与度量导出（默认 true，当类路径存在 MeterRegistry 时生效）
         */
        private boolean enabled = true;
    }
}
