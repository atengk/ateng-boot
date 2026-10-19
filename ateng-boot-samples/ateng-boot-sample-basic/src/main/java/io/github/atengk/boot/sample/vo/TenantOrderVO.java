package io.github.atengk.boot.sample.vo;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 多租户订单展示视图对象 (VO)
 * 传输边界专用对外暴露契约，与持久层 TenantOrderEntity 彻底解耦 (Zero-Entity-Leakage)
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString
public class TenantOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单唯一标识
     */
    private Long id;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 归属租户 ID
     */
    private String tenantId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
