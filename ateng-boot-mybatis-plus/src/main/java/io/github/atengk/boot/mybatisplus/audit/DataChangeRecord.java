package io.github.atengk.boot.mybatisplus.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据变更审计记录模型
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataChangeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 变更数据表名
     */
    private String tableName;

    /**
     * 变更实体类全限定名
     */
    private String entityClass;

    /**
     * 业务主键 ID
     */
    private Object entityId;

    /**
     * 变更类型（UPDATE, DELETE）
     */
    private ChangeType changeType;

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
     * 是否触发超限熔断降级（true 表示受影响行数超限，降级为元数据摘要记录）
     */
    private boolean degraded;

    /**
     * 业务描述
     */
    private String description;

    /**
     * 字段级 Diff 明细列表（仅在 UPDATE 且未熔断时包含）
     */
    private List<FieldDiff> diffs;

    /**
     * 变更前原始快照（属性名 -> 原始值）
     */
    private Map<String, Object> beforeSnapshot;

    /**
     * 变更后新快照（属性名 -> 新值）
     */
    private Map<String, Object> afterSnapshot;
}
