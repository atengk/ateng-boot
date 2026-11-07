package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.enums.SqlTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 结构化操作/SQL 审计与异常诊断响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditAndDiagnoseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全链路追踪 TraceId
     */
    private String traceId;

    /**
     * 结构化操作审计模型
     */
    private OperateLogModel operateLog;

    /**
     * Spring 领域事件总线已消费事件累计计数
     */
    private Integer eventConsumedCount;

    /**
     * Spring 领域事件监听器最新捕获的审计模型摘要
     */
    private OperateLogModel consumedEventModel;

    /**
     * 结构化物理 SQL 审计元数据模型
     */
    private SqlLogModel sqlLog;

    /**
     * 解析推导出的 SQL 操作动作类型
     */
    private SqlTypeEnum sqlType;

    /**
     * 当前 SQL 是否属于 DML 写操作
     */
    private Boolean dml;

    /**
     * 当前 SQL 是否属于只读查询
     */
    private Boolean query;

    /**
     * 深度嵌套异常链的根因异常类名
     */
    private String rootCauseClass;

    /**
     * 根因异常自解释消息
     */
    private String rootCauseMessage;

    /**
     * 有界安全截断异常堆栈文本
     */
    private String truncatedStackTrace;

    /**
     * 异常堆栈是否发生超长截断
     */
    private Boolean stackTruncated;
}
