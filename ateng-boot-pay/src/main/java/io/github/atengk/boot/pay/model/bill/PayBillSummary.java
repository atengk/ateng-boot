package io.github.atengk.boot.pay.model.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 财务对账单整体汇总数据
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY / ALIPAY)
     */
    private PayChannel channel;

    /**
     * 账单日期 (如 2026-10-24)
     */
    private String billDate;

    /**
     * 总交易单数 / 笔数
     */
    private Long totalCount;

    /**
     * 订单总金额 (元)
     */
    private BigDecimal totalAmount;

    /**
     * 应结/实收总金额 (元)
     */
    private BigDecimal settlementAmount;

    /**
     * 退款总金额 (元)
     */
    private BigDecimal refundAmount;

    /**
     * 手续费/服务费总金额 (元)
     */
    private BigDecimal serviceFee;

    /**
     * 原始汇总键值对映射
     */
    @Builder.Default
    private Map<String, String> rawSummary = new LinkedHashMap<>();
}
