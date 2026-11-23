package io.github.atengk.boot.sample.flyway.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 平台租户注册表持久层实体（master 主库，严禁透出 Controller 边界）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("sys_tenant_registry")
public class TenantRegistryEntity extends BaseIdEntity {

    @TableField("tenant_code")
    private String tenantCode;

    @TableField("tenant_name")
    private String tenantName;

    @TableField("ds_key")
    private String dsKey;

    @TableField("plan_tier")
    private String planTier;

    @TableField("source_ds")
    private String sourceDs;

    @TableField("status")
    private String status;
}
