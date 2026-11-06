package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.core.audit.SqlLogModel;
import org.springframework.context.ApplicationEvent;

/**
 * 物理 SQL 操作审计事件
 * 当物理 SQL 执行完成（或发生异常失败现场捕获）并触发结构化存储时，通过 Spring 事件总线广播。
 * 便于单体应用内部各模块基于 {@code @EventListener} 实现非侵入性解耦消费与异步流转。
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class SqlLogEvent extends ApplicationEvent {

    private final SqlLogModel logModel;

    /**
     * 构造 SQL 操作审计事件
     *
     * @param source   事件源对象
     * @param logModel 结构化物理 SQL 操作审计元数据模型
     */
    public SqlLogEvent(Object source, SqlLogModel logModel) {
        super(source);
        this.logModel = logModel;
    }

    /**
     * 获取结构化物理 SQL 操作审计元数据模型
     *
     * @return 结构化物理 SQL 操作审计元数据模型
     */
    public SqlLogModel getLogModel() {
        return logModel;
    }
}
