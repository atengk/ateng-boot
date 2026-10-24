package io.github.atengk.boot.sample.datasource.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 订单持久层实体 (严格隔离于持久层内部)
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_order")
public class OrderEntity extends BaseIdEntity {

    /**
     * 业务订单号
     */
    @TableField("order_no")
    private String orderNo;

    /**
     * 归属租户标识
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 商品名称
     */
    @TableField("product_name")
    private String productName;

    /**
     * 订单总金额 (元)
     */
    @TableField("amount")
    private BigDecimal amount;

    /**
     * 订单状态
     */
    @TableField("status")
    private String status;
}
