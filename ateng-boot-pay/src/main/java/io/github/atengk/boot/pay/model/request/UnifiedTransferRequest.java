package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 统一单笔资金转账高敏请求参数
 * <p>
 * 抹平微信支付（商家转账到零钱）与支付宝（单笔转账到支付宝账户/银行卡）的协议差异。
 * </p>
 * <p>
 * <b>PII 隐私与“内存真明文”不变量</b>：
 * 收款人真实姓名 {@code payeeRealName} 与收款账号 {@code payeeAccount} 在内存中保持 100% 原始真实明文，
 * 供各渠道底层 SDK 执行 RSA/公钥密文出站加密；在 {@link #toString()} 与日志审计中自动通过 {@link DesensitizeUtil} 严格脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedTransferRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道（WECHAT_PAY 或 ALIPAY，未指定时从 PayContextHolder 探测）
     */
    private PayChannel channel;

    /**
     * 商户配置键或应用别名（可选，未指定时采用渠道默认商户/应用）
     */
    private String merchantKey;

    /**
     * 商户转账唯一单号 / 商家批次单号
     */
    private String outBizNo;

    /**
     * 转账金额（元，必须大于 0，小数位不得超过 2 位）
     */
    private BigDecimal amount;

    /**
     * 转账业务标题 / 批次名称
     */
    private String title;

    /**
     * 转账业务备注 / 明细说明
     */
    private String remark;

    /**
     * 收款方账号标识（内存真明文）：
     * - 微信支付：收款用户的 openid；
     * - 支付宝：支付宝登录号（手机/邮箱）、2088 会员 ID、OpenId 或银行卡号。
     */
    private String payeeAccount;

    /**
     * 收款方真实姓名（内存真明文，可选）：
     * - 若传入，微信和支付宝均会强校验收款账号与姓名一致性；转账到银行卡时必传。
     */
    private String payeeRealName;

    /**
     * 收款账号类型（主要用于支付宝，可选值：ALIPAY_LOGON_ID、ALIPAY_USER_ID、ALIPAY_OPEN_ID、BANKCARD_ACCOUNT，默认自动推导或 ALIPAY_LOGON_ID）
     */
    private String payeeIdentityType;

    /**
     * 异步结果通知地址（可选）
     */
    private String notifyUrl;

    /**
     * 扩展透传参数（可选）
     */
    private Map<String, Object> extra;

    @Override
    public String toString() {
        return "UnifiedTransferRequest{" +
                "channel=" + channel +
                ", merchantKey='" + merchantKey + '\'' +
                ", outBizNo='" + outBizNo + '\'' +
                ", amount=" + amount +
                ", title='" + title + '\'' +
                ", remark='" + remark + '\'' +
                ", payeeAccount='" + DesensitizeUtil.maskKeepHeadTail(payeeAccount, 2, 2) + '\'' +
                ", payeeRealName='" + DesensitizeUtil.chineseName(payeeRealName) + '\'' +
                ", payeeIdentityType='" + payeeIdentityType + '\'' +
                ", notifyUrl='" + notifyUrl + '\'' +
                '}';
    }
}
