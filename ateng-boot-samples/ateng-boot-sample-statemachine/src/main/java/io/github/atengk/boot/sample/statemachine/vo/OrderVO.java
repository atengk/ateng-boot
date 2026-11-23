package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单展示视图对象（Zero-Entity-Leakage 契约守卫）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO {

    /**
     * 订单主键 ID
     */
    private Long id;

    /**
     * 业务订单编号
     */
    private String orderSn;

    /**
     * 当前订单生命周期状态
     */
    private OrderState status;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 物流运单号
     */
    private String trackingNo;

    /**
     * 物流轨迹最新备注
     */
    private String trackingRemark;

    /**
     * 风控评分
     */
    private Integer riskScore;

    /**
     * CAS 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createdTime;

    /**
     * 更新时间
     */
    private LocalDateTime updatedTime;

    /**
     * 从持久化实体安全转译为展示视图对象
     *
     * @param entity 订单持久化实体
     * @return 订单视图对象
     */
    public static OrderVO fromDO(OrderDO entity) {
        if (entity == null) {
            return null;
        }
        return OrderVO.builder()
                .id(entity.getId())
                .orderSn(entity.getOrderSn())
                .status(entity.getStatus())
                .amount(entity.getAmount())
                .trackingNo(entity.getTrackingNo())
                .trackingRemark(entity.getTrackingRemark())
                .riskScore(entity.getRiskScore())
                .version(entity.getVersion())
                .createdTime(entity.getCreatedTime())
                .updatedTime(entity.getUpdatedTime())
                .build();
    }
}
