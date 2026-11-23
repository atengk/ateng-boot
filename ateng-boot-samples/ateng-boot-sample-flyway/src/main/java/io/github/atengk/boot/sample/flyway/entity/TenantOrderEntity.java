package io.github.atengk.boot.sample.flyway.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * SaaS 租户订单表持久层实体（tenant_* 物理租户分库，严禁透出 Controller 边界）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("biz_tenant_order")
public class TenantOrderEntity extends BaseIdEntity {

    @TableField("order_no")
    private String orderNo;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("source_ds")
    private String sourceDs;

    @TableField("product_name")
    private String productName;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("status")
    private String status;
}
