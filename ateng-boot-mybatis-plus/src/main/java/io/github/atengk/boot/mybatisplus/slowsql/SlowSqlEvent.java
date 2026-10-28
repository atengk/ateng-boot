package io.github.atengk.boot.mybatisplus.slowsql;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 慢 SQL 性能审计事件
 * 继承 Spring 原生 ApplicationEvent，承载超时 SQL 的执行耗时、格式化 SQL 文本、
 * 脱敏后的绑定参数、线程名称与首个业务调用栈位置
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
@ToString
public class SlowSqlEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * MappedStatement 唯一标识（如 io.github.atengk.boot.mapper.UserMapper.selectById）
     */
    private final String statementId;

    /**
     * 执行的 SQL 语句文本（支持多行美化缩进）
     */
    private final String sql;

    /**
     * 绑定的参数列表（经过脱敏处理）
     */
    private final List<Object> parameters;

    /**
     * 实际执行真实耗时
     */
    private final Duration executionTime;

    /**
     * 慢 SQL 判定阈值
     */
    private final Duration threshold;

    /**
     * 触发事件的时间戳
     */
    private final Instant triggerTime;

    /**
     * 执行线程名称
     */
    private final String threadName;

    /**
     * 业务代码首个调用栈位置（如 com.example.service.UserService.getById(UserService.java:35)）
     */
    private final String callLocation;

    public SlowSqlEvent(Object source,
                        String statementId,
                        String sql,
                        List<Object> parameters,
                        Duration executionTime,
                        Duration threshold,
                        Instant triggerTime,
                        String threadName,
                        String callLocation) {
        super(source);
        this.statementId = statementId;
        this.sql = sql;
        this.parameters = (parameters != null) ? List.copyOf(parameters) : List.of();
        this.executionTime = executionTime;
        this.threshold = threshold;
        this.triggerTime = triggerTime;
        this.threadName = threadName;
        this.callLocation = callLocation;
    }
}
