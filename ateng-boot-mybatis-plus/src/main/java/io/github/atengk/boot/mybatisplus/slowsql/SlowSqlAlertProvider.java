package io.github.atengk.boot.mybatisplus.slowsql;

/**
 * 慢 SQL 告警通知扩展 SPI 函数式接口
 * 供下游业务无缝对接企业内部告警信道（如飞书群机器人、钉钉群、企业微信）或外部 APM 性能监控平台
 *
 * @author Ateng
 * @since 2026-10-18
 */
@FunctionalInterface
public interface SlowSqlAlertProvider {

    /**
     * 接收并处理慢 SQL 审计告警事件
     *
     * @param event 慢 SQL 事件元数据
     */
    void onSlowSql(SlowSqlEvent event);
}
