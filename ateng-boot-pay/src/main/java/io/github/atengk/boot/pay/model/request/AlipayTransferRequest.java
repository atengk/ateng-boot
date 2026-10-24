package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付宝单笔转账专有请求参数
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayTransferRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用别名或 AppId（可选）
     */
    private String appKey;

    /**
     * 商家唯一转账订单号
     */
    private String outBizNo;

    /**
     * 订单总金额（元，必须大于 0）
     */
    private BigDecimal amount;

    /**
     * 转账业务标题（在支付宝账单中展示）
     */
    private String orderTitle;

    /**
     * 业务备注
     */
    private String remark;

    /**
     * 业务产品码：
     * <ul>
     *     <li>TRANS_ACCOUNT_NO_PWD：单笔无密转账到支付宝账户（默认）</li>
     *     <li>TRANS_BANKCARD_NO_PWD：单笔无密转账到银行卡</li>
     * </ul>
     */
    private String productCode;

    /**
     * 业务场景，固定为 DIRECT_TRANSFER
     */
    private String bizScene;

    /**
     * 收款方账号类型：
     * <ul>
     *     <li>ALIPAY_LOGON_ID：支付宝登录账号（手机/邮箱，默认）</li>
     *     <li>ALIPAY_USER_ID：支付宝用户 2088 会员 ID</li>
     *     <li>ALIPAY_OPEN_ID：支付宝 open_id</li>
     *     <li>BANKCARD_ACCOUNT：银行卡账号</li>
     * </ul>
     */
    private String payeeType;

    /**
     * 收款方账号（内存真明文）：支付宝账号、UID 或银行卡号
     */
    private String payeeAccount;

    /**
     * 收款方真实姓名（内存真明文，可选，转账到银行卡必填）
     */
    private String payeeRealName;

    @Override
    public String toString() {
        return "AlipayTransferRequest{" +
                "appKey='" + appKey + '\'' +
                ", outBizNo='" + outBizNo + '\'' +
                ", amount=" + amount +
                ", orderTitle='" + orderTitle + '\'' +
                ", remark='" + remark + '\'' +
                ", productCode='" + productCode + '\'' +
                ", bizScene='" + bizScene + '\'' +
                ", payeeType='" + payeeType + '\'' +
                ", payeeAccount='" + DesensitizeUtil.maskKeepHeadTail(payeeAccount, 2, 2) + '\'' +
                ", payeeRealName='" + DesensitizeUtil.chineseName(payeeRealName) + '\'' +
                '}';
    }
}
