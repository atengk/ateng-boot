package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;

import java.math.BigDecimal;

/**
 * 多租户订单测试实体
 *
 * @author Ateng
 * @since 2026-10-16
 */
@TableName("sys_tenant_order")
public class TenantOrderEntity extends BaseEntity implements TenantEntity {

    private String orderNo;

    private BigDecimal amount;

    private String tenantId;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
