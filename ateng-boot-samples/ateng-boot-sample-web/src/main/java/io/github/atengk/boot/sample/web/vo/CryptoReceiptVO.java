package io.github.atengk.boot.sample.web.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 加密交易回执视图对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoReceiptVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单号
     */
    private String orderNo;

    /**
     * 目标账号（用于示例展示处理结果）
     */
    private String accountNo;

    /**
     * 结算金额
     */
    private BigDecimal amount;

    /**
     * 经过 XSS 清洗或业务处理后的备注
     */
    private String remark;

    /**
     * 处理模式标签
     */
    private String securityChannel;
}
