package io.github.atengk.boot.sample.flyway.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 平台审计日志表持久层实体（log_db 独立业务库，严禁透出 Controller 边界）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("biz_audit_log")
public class AuditLogEntity extends BaseIdEntity {

    @TableField("source_ds")
    private String sourceDs;

    @TableField("audit_scope")
    private String auditScope;

    @TableField("action_name")
    private String actionName;

    @TableField("operator_name")
    private String operatorName;

    @TableField("detail")
    private String detail;
}
