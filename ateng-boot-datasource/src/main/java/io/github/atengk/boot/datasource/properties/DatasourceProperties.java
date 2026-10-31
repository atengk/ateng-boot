package io.github.atengk.boot.datasource.properties;

import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 动态多数据源与物理隔离核心配置属性
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.datasource")
public class DatasourceProperties {

    /**
     * 是否启用 ateng-boot-datasource 增强底座
     */
    private boolean enabled = true;

    /**
     * SaaS 物理多租户分库配置
     */
    @NestedConfigurationProperty
    private MultiTenancyProperties multiTenancy = new MultiTenancyProperties();

    /**
     * 主从读写分离与一致性配置
     */
    @NestedConfigurationProperty
    private ReadWriteSplitProperties readWriteSplit = new ReadWriteSplitProperties();

    /**
     * 多数据源协同守卫与事务安全配置
     */
    @NestedConfigurationProperty
    private GuardProperties guard = new GuardProperties();

    /**
     * 运行时动态增删数据源受管生命周期配置
     */
    @NestedConfigurationProperty
    private DynamicLoadProperties dynamicLoad = new DynamicLoadProperties();

    /**
     * 数据源凭据安全与密文解密配置
     */
    @NestedConfigurationProperty
    private SecurityProperties security = new SecurityProperties();

    /**
     * 分布式事务 Seata 桥接配置
     */
    @NestedConfigurationProperty
    private SeataProperties seata = new SeataProperties();

    /**
     * 动态多数据源连接池运行指标与度量透视配置
     */
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * SaaS 物理多租户隔离配置
     */
    @Data
    public static class MultiTenancyProperties {

        /**
         * 是否启用物理多租户透明切库
         */
        private boolean enabled = true;

        /**
         * 多租户隔离模式（STRICT: 严格独立库; FALLBACK_MASTER: 混合模式回退主库走行级隔离; IGNORE: 免租户路由主库）
         */
        private MultiTenancyMode mode = MultiTenancyMode.FALLBACK_MASTER;

        /**
         * 默认主数据源标识
         */
        private String defaultDataSource = "master";

        /**
         * 租户数据源标识前缀（例如 "tenant_" 则租户 1001 对应 "tenant_1001"）
         */
        private String dataSourcePrefix = "tenant_";
    }

    /**
     * 主从读写分离与主从延迟防卫配置
     */
    @Data
    public static class ReadWriteSplitProperties {

        /**
         * 是否启用读写分离自动路由（联动 @Transactional(readOnly = true) 路由从库）
         */
        private boolean enabled = true;

        /**
         * 主库数据源标识（写库）
         */
        private String masterDataSource = "master";

        /**
         * 从库数据源或数据源分组标识（读库组）
         */
        private String slaveDataSource = "slave";

        /**
         * 写后即读（Read-After-Write）防延迟穿透配置
         */
        @NestedConfigurationProperty
        private ReadAfterWriteProperties readAfterWrite = new ReadAfterWriteProperties();

        /**
         * 从库故障自愈熔断配置
         */
        @NestedConfigurationProperty
        private FailoverProperties failover = new FailoverProperties();
    }

    /**
     * 写后即读防主从延迟配置
     */
    @Data
    public static class ReadAfterWriteProperties {

        /**
         * 是否启用写后即读防延迟机制
         */
        private boolean enabled = true;

        /**
         * 防延迟生效作用域（REQUEST: 单次 HTTP 请求内; WINDOW: 时间窗口内）
         */
        private ReadAfterWriteScope scope = ReadAfterWriteScope.REQUEST;

        /**
         * 时间窗口模式下的 TTL 保持时间（默认 3 秒）
         */
        private Duration windowTtl = Duration.ofSeconds(3);
    }

    /**
     * 从库故障熔断自愈配置
     */
    @Data
    public static class FailoverProperties {

        /**
         * 是否启用从库故障感知与自动黑名单熔断
         */
        private boolean enabled = true;

        /**
         * 故障从库熔断冷却隔离时间（默认 30 秒，期间跳过向该从库路由）
         */
        private Duration cooldownWindow = Duration.ofSeconds(30);

        /**
         * 当所有从库均不可用时，是否自动保底降级路由至主库
         */
        private boolean fallbackToMaster = true;

        /**
         * 是否启用后台受管主动探活自愈机制
         */
        private boolean activeProbeEnabled = true;

        /**
         * 主动探活初始检测周期（默认 5 秒）
         */
        private Duration probeInitialInterval = Duration.ofSeconds(5);

        /**
         * 主动探活最大退避周期（默认 30 秒）
         */
        private Duration probeMaxInterval = Duration.ofSeconds(30);

        /**
         * 主动探活单次物理连接校验超时时间（默认 1 秒）
         */
        private Duration probeTimeout = Duration.ofSeconds(1);
    }

    /**
     * 多数据源协同守卫与事务安全配置
     */
    @Data
    public static class GuardProperties {

        /**
         * 是否启用数据源协同守卫
         */
        private boolean enabled = true;

        /**
         * 在 Spring 原生单物理连接事务中检测到切源行为（假切源）时，是否立即抛出异常快速阻断
         */
        private boolean failOnFalseSwitch = true;
    }

    /**
     * 运行时动态增删数据源受管生命周期配置
     */
    @Data
    public static class DynamicLoadProperties {

        /**
         * 是否启用动态加载与受管生命周期
         */
        private boolean enabled = true;

        /**
         * 新增数据源时的前置连通性探活超时时间（默认 2 秒）
         */
        private Duration probeTimeout = Duration.ofSeconds(2);

        /**
         * 注销数据源时等待在途事务连接归还的优雅排空超时时间（默认 5 秒）
         */
        private Duration drainTimeout = Duration.ofSeconds(5);

        /**
         * 是否在动态挂载数据源时自动预热物理连接池（消除首批请求冷启动排队抖动）
         */
        private boolean preWarm = false;

        /**
         * 动态挂载数据源时预热的物理连接数量（默认 5）
         */
        private int preWarmConnections = 5;

        /**
         * 核心受保护不可删除数据源白名单（默认包含 master, slave 及系统 primary）
         */
        private Set<String> coreWhitelist = new LinkedHashSet<>(Arrays.asList("master", "slave"));

        /**
         * 外部数据源定时自动差量同步配置
         */
        @NestedConfigurationProperty
        private AutoSyncProperties autoSync = new AutoSyncProperties();
    }

    /**
     * 外部数据源定时自动差量同步配置
     */
    @Data
    public static class AutoSyncProperties {

        /**
         * 是否启用外部数据源定时自动差量同步 (Smart Diff)
         */
        private boolean enabled = false;

        /**
         * 自动差量同步轮询执行间隔（默认 1 分钟）
         */
        private Duration interval = Duration.ofMinutes(1);
    }

    /**
     * 数据源安全与凭据解密配置
     */
    @Data
    public static class SecurityProperties {

        /**
         * 是否启用密码密文自动解密（支持 enc:sm4: 与 enc:aes: 前缀）
         */
        private boolean passwordDecryptionEnabled = true;

        /**
         * 密文密码解密全局密钥（可选；若密文字符串未自包含密钥时作为统一对称解密密钥，支持 16/24/32 字节）
         */
        private String secretKey;

        /**
         * 密文密码解密全局初始向量（可选；CBC 模式缺省 IV）
         */
        private String iv;
    }

    /**
     * 分布式事务 Seata 桥接配置
     */
    @Data
    public static class SeataProperties {

        /**
         * 是否启用 Seata 分布式事务透明代理包裹
         */
        private boolean enabled = false;
    }

    /**
     * 动态多数据源 Micrometer 指标自动透视与生命周期配置
     */
    @Data
    public static class MetricsProperties {

        /**
         * 是否启用动态多数据源 Micrometer 连接池指标自动透视与生命周期绑定
         */
        private boolean enabled = true;
    }
}
