package io.github.atengk.boot.mybatisplus.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 日志打印与可执行带参诊断拦截器配置属性
 * 提供 SQL 控制台诊断看板、敏感字段脱敏掩码、代码行号定位、忽略排除规则
 * 以及结构化 SPI 审计持久化（{@link StorageProperties}）的细粒度双轨配置。
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class SqlLogProperties {

    /**
     * 是否启用 SQL 日志与诊断拦截器总开关（默认 false，遵循防灾铁律避免生产隐性资源损耗）
     */
    private boolean enabled = false;

    /**
     * 是否在控制台输出 ASCII 诊断看板日志（默认 true，false 表示仅静默运行或仅供 SPI 存储）
     */
    private boolean consoleEnabled = true;

    /**
     * 控制台日志输出级别（支持 INFO 与 DEBUG，默认 INFO，开箱即用无需配置包名等级）
     */
    private String level = "INFO";

    /**
     * 是否对 SQL 进行多行缩进美化格式化（默认 true，false 为单行压缩紧凑模式）
     */
    private boolean formatSql = true;

    /**
     * 是否对 @FieldEncrypt 实体加密属性及密码凭据等敏感参数进行脱敏掩码（默认 true）
     */
    private boolean maskSensitive = true;

    /**
     * 是否在控制台看板输出业务 Service 调用的代码位置与行号（默认 true）
     */
    private boolean showCallLocation = true;

    /**
     * 控制台单条 SQL 打印最大字符长度限制（默认 4096 字符，防超大批量插入打爆控制台，小于等于 0 表示不限制）
     */
    private int maxSqlLength = 4096;

    /**
     * 全局排除拦截的 MappedStatementId 通配规则清单（如 ["*.selectHeartbeat", "io.github.atengk.boot.job.*"]）
     */
    private List<String> excludeStatements = new ArrayList<>();

    /**
     * 结构化物理 SQL 操作审计 SPI 存储持久化配置
     */
    @NestedConfigurationProperty
    private StorageProperties storage = new StorageProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isConsoleEnabled() {
        return consoleEnabled;
    }

    public void setConsoleEnabled(boolean consoleEnabled) {
        this.consoleEnabled = consoleEnabled;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
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

    public boolean isShowCallLocation() {
        return showCallLocation;
    }

    public void setShowCallLocation(boolean showCallLocation) {
        this.showCallLocation = showCallLocation;
    }

    public int getMaxSqlLength() {
        return maxSqlLength;
    }

    public void setMaxSqlLength(int maxSqlLength) {
        this.maxSqlLength = maxSqlLength;
    }

    public List<String> getExcludeStatements() {
        return excludeStatements;
    }

    public void setExcludeStatements(List<String> excludeStatements) {
        this.excludeStatements = excludeStatements != null ? excludeStatements : new ArrayList<>();
    }

    public StorageProperties getStorage() {
        return storage;
    }

    public void setStorage(StorageProperties storage) {
        this.storage = storage != null ? storage : new StorageProperties();
    }

    /**
     * 结构化物理 SQL 操作审计 SPI 存储持久化配置属性
     */
    public static class StorageProperties {

        /**
         * 是否启用物理 SQL 操作审计 SPI 结构化存储派发（默认 false，按需开启）
         */
        private boolean enabled = false;

        /**
         * 是否将只读 SELECT 查询纳入 SPI 存储审计流转（默认 false，遵循防灾铁律仅记录 INSERT/UPDATE/DELETE 写操作，防存储暴涨）
         */
        private boolean includeSelect = false;

        /**
         * 是否通过受管异步线程池调度派发（默认 true，携带四维上下文异步投递，防阻塞核心业务事务）
         */
        private boolean async = true;

        /**
         * 结构化存储派发时的 SQL 最大字符长度限制（默认 8192 字符，小于等于 0 表示不限制）
         */
        private int maxSqlLength = 8192;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludeSelect() {
            return includeSelect;
        }

        public void setIncludeSelect(boolean includeSelect) {
            this.includeSelect = includeSelect;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }

        public int getMaxSqlLength() {
            return maxSqlLength;
        }

        public void setMaxSqlLength(int maxSqlLength) {
            this.maxSqlLength = maxSqlLength;
        }
    }
}
