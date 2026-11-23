package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租户订单响应视图对象
 * 解耦持久层实体 TenantOrderEntity，供 Controller 传输层直接返回
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class TenantOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单主键 ID
     */
    private Long id;

    /**
     * 业务订单编号
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
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 归属租户 ID
     */
    private String tenantId;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 将持久层实体转换为安全传输视图
     *
     * @param entity 租户订单实体
     * @return 订单视图对象
     */
    public static TenantOrderVO fromEntity(TenantOrderEntity entity) {
        if (entity == null) {
            return null;
        }
        TenantOrderVO vo = new TenantOrderVO();
        vo.setId(entity.getId());
        vo.setOrderNo(entity.getOrderNo());
        vo.setOrderTitle(entity.getOrderTitle());
        vo.setSkuCode(entity.getSkuCode());
        vo.setAmount(entity.getAmount());
        vo.setStatus(entity.getStatus());
        vo.setTenantId(entity.getTenantId());
        vo.setVersion(entity.getVersion());
        vo.setCreateTime(entity.getCreateTime());
        vo.setCreateBy(entity.getCreateBy());
        return vo;
    }
}
