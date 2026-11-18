package io.github.atengk.boot.pay.model.callback;

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
 * 标准化强类型商家转账结果异步回调事件模型
 * <p>
 * 经过网关验签、AES-256-GCM 解密与并发防重锁控制后，针对微信新版商家转账单据终态通知（{@code MCHTRANSFER.BILL.FINISHED}）
 * 转换并分发给业务方 {@link io.github.atengk.boot.pay.spi.PayCallbackListener#onTransferNotify} 的领域事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferNotifyCallback implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    private PayChannel channel;

    /**
     * 商户标识键或应用标识
     */
    private String merchantKey;

    /**
     * 商户转账单号 (out_bill_no / outBizNo)
     */
    private String outBizNo;

    /**
     * 渠道转账流水号 (微信 transfer_bill_no)
     */
    private String orderId;

    /**
     * 标准化转账生命周期状态枚举 (SUCCESS, FAIL, CANCELLED 等)
     */
    private PayTransferState status;

    /**
     * 渠道原生单据状态字符串
     */
    private String rawStatus;

    /**
     * 转账金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal amount;

    /**
     * 收款用户标识 (如微信 OpenID)
     */
    private String payeeAccount;

    /**
     * 转账单状态变更/完成时间 (格式标准化字符串)
     */
    private String payTime;

    /**
     * 失败或撤销原因描述 (当 status 为 FAIL 或 CANCELLED 时可能存在)
     */
    private String failReason;

    /**
     * 渠道原始回调载荷对象
     */
    private Object rawPayload;

    @Override
    public String toString() {
        return "TransferNotifyCallback{" +
                "channel=" + channel +
                ", merchantKey='" + merchantKey + '\'' +
                ", outBizNo='" + outBizNo + '\'' +
                ", orderId='" + orderId + '\'' +
                ", status=" + status +
                ", rawStatus='" + rawStatus + '\'' +
                ", amount=" + amount +
                ", payeeAccount='" + DesensitizeUtil.maskKeepHeadTail(payeeAccount, 2, 2) + '\'' +
                ", payTime='" + payTime + '\'' +
                ", failReason='" + failReason + '\'' +
                '}';
    }
}
