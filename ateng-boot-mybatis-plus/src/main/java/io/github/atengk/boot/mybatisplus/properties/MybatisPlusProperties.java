package io.github.atengk.boot.mybatisplus.properties;

import com.baomidou.mybatisplus.annotation.DbType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-Plus 模块配置属性
 * 提供审计填充、分页限额防刷、防全表更新删除、乐观锁插件与雪花发号器的全局可控开关
 *
 * @author Ateng
 * @since 2026-10-15
 */
@ConfigurationProperties(prefix = "ateng.boot.mybatis-plus")
public class MybatisPlusProperties {

    /**
     * 是否启用 MyBatis-Plus 模块自动装配（默认 true）
     */
    private boolean enabled = true;

    /**
     * 慢 SQL 性能审计与告警拦截器配置
     */
    @NestedConfigurationProperty
    private SlowSqlProperties slowSql = new SlowSqlProperties();

    /**
     * 行级多租户插件配置
     */
    @NestedConfigurationProperty
    private TenantProperties tenant = new TenantProperties();

    /**
     * 细粒度数据权限插件配置
     */
    @NestedConfigurationProperty
    private DataPermissionProperties dataPermission = new DataPermissionProperties();

    /**
     * 原生批量操作属性配置
     */
    @NestedConfigurationProperty
    private BatchProperties batch = new BatchProperties();

    /**
     * 自动审计填充配置
     */
    @NestedConfigurationProperty
    private AuditProperties audit = new AuditProperties();

    /**
     * 分页插件配置
     */
    @NestedConfigurationProperty
    private PaginationProperties pagination = new PaginationProperties();

    /**
     * 防全表更新/删除插件配置
     */
    @NestedConfigurationProperty
    private BlockAttackProperties blockAttack = new BlockAttackProperties();

    /**
     * 乐观锁插件配置
     */
    @NestedConfigurationProperty
    private OptimisticLockerProperties optimisticLocker = new OptimisticLockerProperties();

    /**
     * 雪花发号器配置
     */
    @NestedConfigurationProperty
    private SnowflakeProperties snowflake = new SnowflakeProperties();

    /**
     * 数据加解密配置
     */
    @NestedConfigurationProperty
    private CryptoProperties crypto = new CryptoProperties();

    /**
     * 外部动态 SQL 增强配置
     */
    @NestedConfigurationProperty
    private DynamicSqlProperties dynamicSql = new DynamicSqlProperties();

    /**
     * 动态查询条件转译引擎配置
     */
    @NestedConfigurationProperty
    private DynamicQueryProperties dynamicQuery = new DynamicQueryProperties();

    /**
     * 持久层守卫与诊断探针配置
     */
    @NestedConfigurationProperty
    private GuardProperties guard = new GuardProperties();

    /**
     * 数据变更 Diff 审计拦截器配置
     */
    @NestedConfigurationProperty
    private AuditChangeProperties auditChange = new AuditChangeProperties();

    /**
     * SQL 日志打印与耗时诊断拦截器配置
     */
    @NestedConfigurationProperty
    private SqlLogProperties sqlLog = new SqlLogProperties();

    /**
     * 逻辑删除穿透与误删恢复配置
     */
    @NestedConfigurationProperty
    private LogicDeleteProperties logicDelete = new LogicDeleteProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SlowSqlProperties getSlowSql() {
        return slowSql;
    }

    public void setSlowSql(SlowSqlProperties slowSql) {
        this.slowSql = slowSql;
    }

    public AuditProperties getAudit() {
        return audit;
    }

    public void setAudit(AuditProperties audit) {
        this.audit = audit;
    }

    public PaginationProperties getPagination() {
        return pagination;
    }

    public void setPagination(PaginationProperties pagination) {
        this.pagination = pagination;
    }

    public BlockAttackProperties getBlockAttack() {
        return blockAttack;
    }

    public void setBlockAttack(BlockAttackProperties blockAttack) {
        this.blockAttack = blockAttack;
    }

    public OptimisticLockerProperties getOptimisticLocker() {
        return optimisticLocker;
    }

    public void setOptimisticLocker(OptimisticLockerProperties optimisticLocker) {
        this.optimisticLocker = optimisticLocker;
    }

    public SnowflakeProperties getSnowflake() {
        return snowflake;
    }

    public void setSnowflake(SnowflakeProperties snowflake) {
        this.snowflake = snowflake;
    }

    public CryptoProperties getCrypto() {
        return crypto;
    }

    public void setCrypto(CryptoProperties crypto) {
        this.crypto = crypto;
    }

    public DynamicSqlProperties getDynamicSql() {
        return dynamicSql;
    }

    public void setDynamicSql(DynamicSqlProperties dynamicSql) {
        this.dynamicSql = dynamicSql;
    }

    public TenantProperties getTenant() {
        return tenant;
    }

    public void setTenant(TenantProperties tenant) {
        this.tenant = tenant;
    }

    public DataPermissionProperties getDataPermission() {
        return dataPermission;
    }

    public void setDataPermission(DataPermissionProperties dataPermission) {
        this.dataPermission = dataPermission;
    }

    public BatchProperties getBatch() {
        return batch;
    }

    public void setBatch(BatchProperties batch) {
        this.batch = batch;
    }

    public GuardProperties getGuard() {
        return guard;
    }

    public void setGuard(GuardProperties guard) {
        this.guard = guard;
    }

    public DynamicQueryProperties getDynamicQuery() {
        return dynamicQuery;
    }

    public void setDynamicQuery(DynamicQueryProperties dynamicQuery) {
        this.dynamicQuery = dynamicQuery;
    }

    public AuditChangeProperties getAuditChange() {
        return auditChange;
    }

    public void setAuditChange(AuditChangeProperties auditChange) {
        this.auditChange = auditChange;
    }

    public SqlLogProperties getSqlLog() {
        return sqlLog;
    }

    public void setSqlLog(SqlLogProperties sqlLog) {
        this.sqlLog = sqlLog;
    }

    public LogicDeleteProperties getLogicDelete() {
        return logicDelete;
    }

    public void setLogicDelete(LogicDeleteProperties logicDelete) {
        this.logicDelete = logicDelete;
    }


    /**
     * 审计填充属性配置
     */
    public static class AuditProperties {
        /**
         * 是否启用自动审计填充，默认 true
         */
        private boolean enabled = true;

        /**
         * 匿名或无上下文操作人默认兜底标识，默认 SYSTEM
         */
        private String defaultOperator = "SYSTEM";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultOperator() {
            return defaultOperator;
        }

        public void setDefaultOperator(String defaultOperator) {
            this.defaultOperator = defaultOperator;
        }
    }

    /**
     * 分页拦截器属性配置
     */
    public static class PaginationProperties {
        /**
         * 是否启用分页拦截器，默认 true
         */
        private boolean enabled = true;

        /**
         * 单页最大数量限制，默认 500
         */
        private Long maxLimit = 500L;

        /**
         * 溢出总页数后是否进行处理，默认 false (继续请求)
         */
        private boolean overflow = false;

        /**
         * 数据库方言类型，默认自动探测
         */
        private DbType dbType;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(Long maxLimit) {
            this.maxLimit = maxLimit;
        }

        public boolean isOverflow() {
            return overflow;
        }

        public void setOverflow(boolean overflow) {
            this.overflow = overflow;
        }

        public DbType getDbType() {
            return dbType;
        }

        public void setDbType(DbType dbType) {
            this.dbType = dbType;
        }
    }

    /**
     * 防全表更新删除拦截器属性配置
     */
    public static class BlockAttackProperties {
        /**
         * 是否启用防全表更新删除拦截器，默认 true
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 乐观锁拦截器属性配置
     */
    public static class OptimisticLockerProperties {
        /**
         * 是否启用乐观锁拦截器，默认 true
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 雪花发号器属性配置
     */
    public static class SnowflakeProperties {
        /**
         * 是否启用雪花算法发号器注册，默认 true
         */
        private boolean enabled = true;

        /**
         * 工作机器节点 ID (0~31)
         */
        private Long workerId;

        /**
         * 数据中心节点 ID (0~31)
         */
        private Long datacenterId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(Long workerId) {
            this.workerId = workerId;
        }

        public Long getDatacenterId() {
            return datacenterId;
        }

        public void setDatacenterId(Long datacenterId) {
            this.datacenterId = datacenterId;
        }
    }

    /**
     * 数据持久化加解密属性配置
     */
    public static class CryptoProperties {
        /**
         * AES 加密密钥（支持 16/24/32 字节原生字符、Base64 编码密钥或任意自定义密钥串）
         * 未配置时自适应默认内置安全密钥
         */
        private String secretKey;

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    /**
     * 行级多租户属性配置
     */
    public static class TenantProperties {
        /**
         * 是否启用行级多租户插件，默认 false
         */
        private boolean enabled = false;

        /**
         * 数据库租户字段列名，默认 tenant_id
         */
        private String column = "tenant_id";

        /**
         * 全局免多租户过滤的静态数据表名清单
         */
        private List<String> ignoreTables = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public List<String> getIgnoreTables() {
            return ignoreTables;
        }

        public void setIgnoreTables(List<String> ignoreTables) {
            this.ignoreTables = ignoreTables;
        }
    }

    /**
     * 细粒度数据权限属性配置
     */
    public static class DataPermissionProperties {
        /**
         * 是否启用细粒度数据权限拦截器，默认 false
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 原生批量操作属性配置
     */
    public static class BatchProperties {
        /**
         * 原生分批批量插入默认切片批次大小，默认 1000
         */
        private int defaultSize = 1000;

        public int getDefaultSize() {
            return defaultSize;
        }

        public void setDefaultSize(int defaultSize) {
            this.defaultSize = defaultSize;
        }
    }

    /**
     * 慢 SQL 性能审计与告警拦截器属性配置
     */
    public static class SlowSqlProperties {
        /**
         * 是否启用慢 SQL 拦截器，默认 true
         */
        private boolean enabled = true;

        /**
         * 慢 SQL 判定执行耗时阈值，默认 1s
         */
        private Duration threshold = Duration.ofSeconds(1);

        /**
         * 是否对 SQL 进行多行缩进美化，默认 true
         */
        private boolean formatSql = true;

        /**
         * 是否对敏感数据（@FieldEncrypt 字段及密码密钥）进行脱敏掩码，默认 true
         */
        private boolean maskSensitive = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getThreshold() {
            return threshold;
        }

        public void setThreshold(Duration threshold) {
            this.threshold = threshold;
        }

        public boolean isFormatSql() {
            return formatSql;
        }

        public void setFormatSql(boolean formatSql) {
            this.formatSql = formatSql;
        }

        public boolean isMaskSensitive() {
            return maskSensitive;
        }

        public void setMaskSensitive(boolean maskSensitive) {
            this.maskSensitive = maskSensitive;
        }
    }

    /**
     * 持久层守卫与诊断探针属性配置
     */
    public static class GuardProperties {
        /**
         * 是否启用多数据源协同诊断探针，默认 true
         */
        private boolean dynamicDatasource = true;

        public boolean isDynamicDatasource() {
            return dynamicDatasource;
        }

        public void setDynamicDatasource(boolean dynamicDatasource) {
            this.dynamicDatasource = dynamicDatasource;
        }
    }

    /**
     * 逻辑删除穿透与恢复拦截器配置
     */
    public static class LogicDeleteProperties {
        /**
         * 是否启用逻辑删除动态穿透与恢复拦截器插件，默认 true
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

