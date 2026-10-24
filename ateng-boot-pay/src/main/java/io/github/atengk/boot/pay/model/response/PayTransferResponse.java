package io.github.atengk.boot.pay.model.response;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTransferState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 统一资金转账响应实体
 * <p>
 * 抹平微信支付与支付宝渠道单笔转账的出参结构差异。
 * </p>
 * <p>
 * <b>PII 隐私保护</b>：
 * 收款人真实姓名 {@code payeeRealName} 与收款账号 {@code payeeAccount} 在 {@link #toString()}
 * 中自动通过 {@link DesensitizeUtil} 脱敏，严防日志与堆栈泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayTransferResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道
     */
    private PayChannel channel;

    /**
     * 商户转账单号 / 商家批次单号
     */
    private String outBizNo;

    /**
     * 渠道官方转账订单号（如微信 batch_id 或 支付宝 order_id）
     */
    private String orderId;

    /**
     * 渠道官方资金流水号（如支付宝 pay_fund_order_id 或 微信 detail_id）
     */
    private String payFundOrderId;

    /**
     * 标准化统一转账状态机
     */
    private PayTransferState status;

    /**
     * 转账金额（元）
     */
    private BigDecimal amount;

    /**
     * 收款方账号（内存明文）
     */
    private String payeeAccount;

    /**
     * 收款方真实姓名（内存明文）
     */
    private String payeeRealName;

    /**
     * 转账受理或到账时间
     */
    private String payTime;

    /**
     * 失败或退票原因描述（转账失败时返回）
     */
    private String failReason;

    /**
     * 错误码（渠道原生错误码）
     */
    private String errorCode;

    /**
     * 渠道原生原始响应对象（逃生舱，保留完整上下文）
     */
    private Object rawResponse;

    @Override
    public String toString() {
        return "PayTransferResponse{" +
                "channel=" + channel +
                ", outBizNo='" + outBizNo + '\'' +
                ", orderId='" + orderId + '\'' +
                ", payFundOrderId='" + payFundOrderId + '\'' +
                ", status=" + status +
                ", amount=" + amount +
                ", payeeAccount='" + DesensitizeUtil.maskKeepHeadTail(payeeAccount, 2, 2) + '\'' +
                ", payeeRealName='" + DesensitizeUtil.chineseName(payeeRealName) + '\'' +
                ", payTime='" + payTime + '\'' +
                ", failReason='" + failReason + '\'' +
                ", errorCode='" + errorCode + '\'' +
                '}';
    }
}
