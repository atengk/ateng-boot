package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.core.audit.SqlLogModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物理 SQL 结构化审计日志视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全链路追踪 TraceId
     */
    private String traceId;

    /**
     * 租户标识
     */
    private String tenantId;

    /**
     * 操作人 ID
     */
    private String userId;

    /**
     * 操作人账号/姓名
     */
    private String userName;

    /**
     * 数据源标识
     */
    private String datasource;

    /**
     * MappedStatement ID
     */
    private String statementId;

    /**
     * 可执行带参 SQL（已脱敏）
     */
    private String sql;

    /**
     * SQL 类型（SELECT / INSERT / UPDATE / DELETE / OTHER）
     */
    private String sqlType;

    /**
     * 执行耗时（毫秒）
     */
    private Long costTime;

    /**
     * 影响或返回行数
     */
    private Long affectedRows;

    /**
     * 执行状态（0-成功，1-失败）
     */
    private Integer status;

    /**
     * 异常错误信息
     */
    private String errorMsg;

    /**
     * 代码调用栈位置
     */
    private String callLocation;

    /**
     * 执行时间
     */
    private LocalDateTime operateTime;

    public static SqlLogVO fromModel(SqlLogModel model) {
        if (model == null) {
            return null;
        }
        SqlLogVO vo = new SqlLogVO();
        vo.setTraceId(model.getTraceId());
        vo.setTenantId(model.getTenantId());
        vo.setUserId(model.getUserId());
        vo.setUserName(model.getUserName());
        vo.setDatasource(model.getDatasource());
        vo.setStatementId(model.getStatementId());
        vo.setSql(model.getSql());
        vo.setSqlType(model.getSqlType() != null ? model.getSqlType().name() : null);
        vo.setCostTime(model.getCostTime());
        vo.setAffectedRows(model.getAffectedRows());
        vo.setStatus(model.getStatus());
        vo.setErrorMsg(model.getErrorMsg());
        vo.setCallLocation(model.getCallLocation());
        vo.setOperateTime(model.getOperateTime());
        return vo;
    }
}
