package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.github.atengk.boot.mybatisplus.annotation.AuditChange;
import io.github.atengk.boot.mybatisplus.annotation.ConflictKeys;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 租户订单实体对象
 * 继承四要素全审计业务基类 BaseEntity，实现 TenantEntity 多租户隔离，
 * 声明 @ConflictKeys 双方言批量 Upsert 与 @AuditChange 数据变更 Diff 审计
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("tenant_order")
@ConflictKeys({"order_no"})
@AuditChange(description = "订单变更审计")
public class TenantOrderEntity extends BaseEntity implements TenantEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号（唯一索引）
     */
    private String orderNo;

    /**
     * 订单标题
     */
    private String orderTitle;

    /**
     * 商品 SKU 编码
     */
    private String skuCode;

    /**
     * 订单交易金额
     */
    private BigDecimal amount;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 租户标识
     */
    private String tenantId;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    public TenantOrderEntity(String orderNo, String orderTitle, String skuCode, BigDecimal amount, String status) {
        this.orderNo = orderNo;
        this.orderTitle = orderTitle;
        this.skuCode = skuCode;
        this.amount = amount;
        this.status = status;
    }
}
