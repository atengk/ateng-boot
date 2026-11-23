package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.mybatisplus.audit.DataChangeRecord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 数据变更 Diff 审计记录视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataChangeAuditVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 变更数据表名
     */
    private String tableName;

    /**
     * 业务主键 ID
     */
    private Object entityId;

    /**
     * 变更类型（UPDATE, DELETE）
     */
    private String changeType;

    /**
     * 当前操作人标识
     */
    private String operator;

    /**
     * 当前租户标识
     */
    private String tenantId;

    /**
     * 全链路跟踪 TraceId
     */
    private String traceId;

    /**
     * 变更发生时间
     */
    private LocalDateTime changeTime;

    /**
     * 受影响行数
     */
    private Integer affectedRows;

    /**
     * 是否触发超限熔断降级
     */
    private boolean degraded;

    /**
     * 业务描述
     */
    private String description;

    /**
     * 字段变更明细列表
     */
    private List<FieldDiffVO> diffs;

    public static DataChangeAuditVO fromRecord(DataChangeRecord record) {
        if (record == null) {
            return null;
        }
        DataChangeAuditVO vo = new DataChangeAuditVO();
        vo.setTableName(record.getTableName());
        vo.setEntityId(record.getEntityId());
        vo.setChangeType(record.getChangeType() != null ? record.getChangeType().name() : null);
        vo.setOperator(record.getOperator());
        vo.setTenantId(record.getTenantId());
        vo.setTraceId(record.getTraceId());
        vo.setChangeTime(record.getChangeTime());
        vo.setAffectedRows(record.getAffectedRows());
        vo.setDegraded(record.isDegraded());
        vo.setDescription(record.getDescription());
        if (record.getDiffs() != null) {
            vo.setDiffs(record.getDiffs().stream().map(FieldDiffVO::fromDiff).toList());
        } else {
            vo.setDiffs(Collections.emptyList());
        }
        return vo;
    }
}
