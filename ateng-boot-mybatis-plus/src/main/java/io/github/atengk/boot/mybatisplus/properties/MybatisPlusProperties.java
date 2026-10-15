package io.github.atengk.boot.mybatisplus.properties;

import com.baomidou.mybatisplus.annotation.DbType;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
     * 自动审计填充配置
     */
    private AuditProperties audit = new AuditProperties();

    /**
     * 分页插件配置
     */
    private PaginationProperties pagination = new PaginationProperties();

    /**
     * 防全表更新/删除插件配置
     */
    private BlockAttackProperties blockAttack = new BlockAttackProperties();

    /**
     * 乐观锁插件配置
     */
    private OptimisticLockerProperties optimisticLocker = new OptimisticLockerProperties();

    /**
     * 雪花发号器配置
     */
    private SnowflakeProperties snowflake = new SnowflakeProperties();

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
}
