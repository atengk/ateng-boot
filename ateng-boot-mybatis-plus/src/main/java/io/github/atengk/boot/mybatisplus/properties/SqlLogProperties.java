package io.github.atengk.boot.mybatisplus.properties;

/**
 * SQL 日志打印与可执行带参诊断拦截器配置属性
 * 提供 SQL 格式化、敏感字段脱敏掩码、代码行号定位及超长 SQL 截断的细粒度开关
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class SqlLogProperties {

    /**
     * 是否启用 SQL 日志打印与耗时诊断拦截器（默认 false，遵循防灾铁律避免生产隐性资源损耗）
     */
    private boolean enabled = false;

    /**
     * 日志输出级别（支持 INFO 与 DEBUG，默认 INFO，开箱即用无需配置包名等级）
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
     * 单条 SQL 打印最大字符长度限制（默认 4096 字符，防超大批量插入打爆控制台，小于等于 0 表示不限制）
     */
    private int maxSqlLength = 4096;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}
