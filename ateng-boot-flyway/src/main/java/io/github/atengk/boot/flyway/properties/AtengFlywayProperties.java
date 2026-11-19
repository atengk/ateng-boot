package io.github.atengk.boot.flyway.properties;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.DynamicMountFailurePolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flyway 数据库版本化迁移企业级治理配置属性
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.flyway")
public class AtengFlywayProperties {

    /**
     * 是否启用 ateng-boot-flyway 增强底座（关闭时完整退让给 Spring Boot 原生 FlywayAutoConfiguration）
     */
    private boolean enabled = true;

    /**
     * 默认主目标数据源标识名称（用于摘要记录与单库默认目标键）
     */
    private String primaryTargetKey = "default";

    /**
     * 启动迁移执行模式（MIGRATE: 自动迁移; VALIDATE_ONLY: 仅校验不改表; DISABLED: 跳过启动迁移）
     */
    private FlywayExecutionMode mode = FlywayExecutionMode.MIGRATE;

    /**
     * 主库与显式纳管静态业务库的失败容错策略（默认 FAIL_FAST，遇异常立即阻断启动）
     */
    private BatchExecutionPolicy staticFailurePolicy = BatchExecutionPolicy.FAIL_FAST;

    /**
     * 分布式互斥锁最大等待超时时间（默认 30 秒）
     */
    private Duration lockWaitTimeout = Duration.ofSeconds(30);

    /**
     * 生产防误操作安全守卫配置
     */
    @NestedConfigurationProperty
    private GuardProperties guard = new GuardProperties();

    /**
     * 隔离式 DDL 迁移凭据配置（高权限瞬态连接，零容器 DataSource/Flyway Bean 污染）
     */
    @NestedConfigurationProperty
    private CredentialProperties credential = new CredentialProperties();

    /**
     * 上下文迁移占位符自动注入配置
     */
    @NestedConfigurationProperty
    private PlaceholderProperties placeholder = new PlaceholderProperties();

    /**
     * 多数据源路由过滤治理配置
     */
    @NestedConfigurationProperty
    private TargetRoutingProperties routing = new TargetRoutingProperties();

    /**
     * 显式纳管的静态多业务库独立迁移配置（dsKey -> 独立迁移参数与凭据覆盖）
     */
    private Map<String, DataSourceMigrationProperties> datasources = new LinkedHashMap<>();

    /**
     * SaaS 物理多租户（L3）启动批量迁移与动态挂载生命周期配置
     */
    @NestedConfigurationProperty
    private TenantMigrationProperties tenant = new TenantMigrationProperties();

    /**
     * 可观测与健康探针配置
     */
    @NestedConfigurationProperty
    private ObservabilityProperties observability = new ObservabilityProperties();

    /**
     * 生产防误操作硬守卫配置
     */
    @Data
    public static class GuardProperties {

        /**
         * 是否启用生产防误操作安全守卫
         */
        private boolean enabled = true;

        /**
         * 受保护的生产环境 Spring Profile 列表（大小写不敏感匹配）
         */
        private List<String> protectedProfiles = new ArrayList<>(Arrays.asList("prod", "production", "prd"));

        /**
         * 在受保护环境下是否物理禁止任何 clean 操作（无视 spring.flyway.clean-disabled=false）
         */
        private boolean forbidCleanInProtectedProfiles = true;

        /**
         * 是否无条件禁止所有环境执行 clean 操作（包括非生产环境）
         */
        private boolean forbidCleanAlways = false;

        /**
         * 在受保护环境下是否强制拦截 spring.flyway.clean-on-validation-error=true
         */
        private boolean forbidCleanOnValidationError = true;

        /**
         * 在受保护环境下是否静态扫描待执行 SQL，拦截未授权的 DROP TABLE/DATABASE/SCHEMA 与 TRUNCATE 语句
         */
        private boolean forbidDestructiveDdlInProtectedProfiles = true;

        /**
         * 是否无条件在所有环境下拦截待执行 SQL 中未授权的高危破坏性 DDL 语句
         */
        private boolean forbidDestructiveDdlAlways = false;

        /**
         * 是否允许经受审脚本在 SQL 注释中标注 {@code -- @ateng-allow-destructive-ddl} 显式豁免放行
         */
        private boolean allowDestructiveDdlAnnotation = true;
    }

    /**
     * 隔离式 DDL 高权限迁移凭据配置
     */
    @Data
    public static class CredentialProperties {

        /**
         * 是否允许激活隔离式 DDL 凭据（仅当同时配置了 url/username/password 之一时生效）
         */
        private boolean enabled = true;

        /**
         * 独立 DDL 连接 JDBC URL（未配置时自动复用主库数据源或 spring.flyway.url）
         */
        private String url;

        /**
         * 可选的 JDBC 驱动全限定类名
         */
        private String driverClassName;

        /**
         * 高权限 DDL 迁移账号用户名
         */
        private String username;

        /**
         * 高权限 DDL 迁移账号口令（支持明文以及 enc:sm4: / enc:aes: 密文）
         */
        private String password;

        /**
         * 全局对称解密密钥（用于单段密文 enc:sm4:<cipher> 或 enc:aes:<cipher>，支持 Base64 / Hex / 明文字符）
         */
        private String secretKey;

        /**
         * 全局初始向量 IV（用于单段 CBC 密文，支持 Base64 / Hex / 明文字符）
         */
        private String iv;

        /**
         * 瞬态 DDL 连接超时时间
         */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /**
         * 判断当前配置是否已激活隔离式 DDL 凭据模式
         *
         * @return true 表示已配置有效隔离凭据
         */
        public boolean isConfigured() {
            return enabled && (StringUtils.hasText(url)
                    || StringUtils.hasText(username)
                    || StringUtils.hasText(password));
        }
    }

    /**
     * 可观测性与健康探针配置
     */
    @Data
    public static class ObservabilityProperties {

        /**
         * 是否注册 Actuator FlywayHealthIndicator 健康探针
         */
        private boolean healthEnabled = true;

        /**
         * 健康探针缓存窗口时间（防止高频探查直击数据库；设为 PT0S 时每次实时探查）
         */
        private Duration healthCacheTtl = Duration.ofSeconds(30);

        /**
         * 是否注册 Micrometer 迁移度量指标绑定器
         */
        private boolean metricsEnabled = true;
    }

    /**
     * 只读上下文占位符自动注入配置
     */
    @Data
    public static class PlaceholderProperties {

        /**
         * 是否在构建 Flyway 配置时自动注入只读上下文占位符（绝不覆盖用户显式配置的同名占位符）
         */
        private boolean enabled = true;

        /**
         * 目标数据源标识占位符变量名（默认 ateng_ds_key）
         */
        private String dsKeyName = "ateng_ds_key";

        /**
         * 租户标识占位符变量名（默认 ateng_tenant_id）
         */
        private String tenantIdName = "ateng_tenant_id";
    }

    /**
     * 多数据源目标路由排除与主从屏蔽配置
     */
    @Data
    public static class TargetRoutingProperties {

        /**
         * 是否永久排除名称为 slave 或 *_slave 的从库数据源（建议保持 true）
         */
        private boolean skipSlave = true;

        /**
         * 是否永久排除读写分离只读集群分组数据源 GroupDataSource（建议保持 true）
         */
        private boolean skipReadOnlyGroup = true;

        /**
         * 额外强制排除的辅助数据源标识列表
         */
        private List<String> excludedDataSources = new ArrayList<>();
    }

    /**
     * 显式纳管静态业务库（L2）的独立 Flyway 参数与 DDL 凭据覆写配置
     */
    @Data
    public static class DataSourceMigrationProperties {

        /**
         * 是否启用该静态数据源的版本化迁移（false 时从迁移目标集合跳过）
         */
        private boolean enabled = true;

        /**
         * 独立脚本扫描路径列表（未配置时按目标标识隔离为 classpath:db/migration/{dsKey}）
         */
        private List<String> locations;

        /**
         * 独立迁移历史记录表名（为空时继承 spring.flyway.table）
         */
        private String table;

        /**
         * 独立受控 Schema 列表（为空时继承 spring.flyway.schemas）
         */
        private List<String> schemas;

        /**
         * 独立默认 Schema（为空时继承 spring.flyway.default-schema）
         */
        private String defaultSchema;

        /**
         * 是否在非空存量库自动执行 Baseline（为空时继承全局配置）
         */
        private Boolean baselineOnMigrate;

        /**
         * 独立 Baseline 版本号（为空时继承全局配置）
         */
        private String baselineVersion;

        /**
         * 独立 Baseline 描述文本（为空时继承全局配置）
         */
        private String baselineDescription;

        /**
         * 是否在迁移前校验历史脚本 Checksum（为空时继承全局配置）
         */
        private Boolean validateOnMigrate;

        /**
         * 是否允许乱序执行脚本（为空时继承全局配置）
         */
        private Boolean outOfOrder;

        /**
         * 独立脚本字符集编码（为空时继承 spring.flyway.encoding）
         */
        private Charset encoding;

        /**
         * 目标级专属 SQL 占位符（优先于全局 spring.flyway.placeholders 及自动注入占位符）
         */
        private Map<String, String> placeholders = new LinkedHashMap<>();

        /**
         * 目标级独立高权限 DDL 迁移凭据（支持 enc:sm4:/enc:aes: 密文）
         */
        @NestedConfigurationProperty
        private CredentialProperties credential = new CredentialProperties();
    }

    /**
     * SaaS 物理多租户（L3）批量迁移与动态挂载治理配置
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @ToString(callSuper = true)
    public static class TenantMigrationProperties extends DataSourceMigrationProperties {

        public TenantMigrationProperties() {
            setEnabled(false);
            setLocations(new ArrayList<>(List.of("classpath:db/migration/tenant")));
        }

        /**
         * 应用启动时是否自动对全部已有物理租户库执行迁移或校验
         */
        private boolean migrateOnStartup = true;

        /**
         * 运行期通过 DynamicDatasourceTemplate 动态挂载新租户库时是否自动触发迁移
         */
        private boolean migrateOnDynamicAdd = true;

        /**
         * 启动期租户批量迁移是否异步执行（默认 false 同步；大租户群可设为 true 以免阻塞启动端口暴露）
         */
        private boolean asyncStartup = false;

        /**
         * 租户批量迁移有界并发度（默认 1 串行；大于 1 时依托 Core 受管线程池与 AsyncContext 并发执行）
         */
        private int concurrency = 1;

        /**
         * 租户批量并发迁移协调最大等待超时时间（默认 10 分钟，严防无界挂起）
         */
        private Duration batchTimeout = Duration.ofMinutes(10);

        /**
         * 租户批量迁移失败容错策略（默认 FAIL_SAFE，单租户失败不影响其余租户及平台主库）
         */
        private BatchExecutionPolicy failurePolicy = BatchExecutionPolicy.FAIL_SAFE;

        /**
         * 运行期动态挂载租户数据源迁移失败时的处置策略（默认 KEEP_AND_REPORT 保留数据源并上报）
         */
        private DynamicMountFailurePolicy dynamicMountFailurePolicy = DynamicMountFailurePolicy.KEEP_AND_REPORT;

        /**
         * 租户数据源名称前缀覆写（为空时自动继承 ateng.boot.datasource.multi-tenancy.data-source-prefix，缺省 tenant_）
         */
        private String dataSourcePrefix;
    }
}
