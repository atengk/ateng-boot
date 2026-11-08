package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 慢 SQL 性能告警事件视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlowSqlEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * MappedStatement ID
     */
    private String statementId;

    /**
     * 执行的 SQL 语句文本
     */
    private String sql;

    /**
     * 绑定的参数列表（敏感已脱敏）
     */
    private List<Object> parameters;

    /**
     * 实际执行耗时（毫秒）
     */
    private Long executionTimeMs;

    /**
     * 判定告警阈值（毫秒）
     */
    private Long thresholdMs;

    /**
     * 触发时间戳
     */
    private Instant triggerTime;

    /**
     * 执行线程名称
     */
    private String threadName;

    /**
     * 代码调用栈位置
     */
    private String callLocation;

    public static SlowSqlEventVO fromEvent(SlowSqlEvent event) {
        if (event == null) {
            return null;
        }
        SlowSqlEventVO vo = new SlowSqlEventVO();
        vo.setStatementId(event.getStatementId());
        vo.setSql(event.getSql());
        vo.setParameters(event.getParameters());
        vo.setExecutionTimeMs(event.getExecutionTime() != null ? event.getExecutionTime().toMillis() : null);
        vo.setThresholdMs(event.getThreshold() != null ? event.getThreshold().toMillis() : null);
        vo.setTriggerTime(event.getTriggerTime());
        vo.setThreadName(event.getThreadName());
        vo.setCallLocation(event.getCallLocation());
        return vo;
    }
}
