package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 微信商家转账到零钱专有请求参数
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatTransferRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户配置别名或商户号（可选）
     */
    private String merchantKey;

    /**
     * 商家批次单号
     */
    private String outBatchNo;

    /**
     * 商家明细单号（单笔转账时若为空则默认同 outBatchNo）
     */
    private String outDetailNo;

    /**
     * 批次名称
     */
    private String batchName;

    /**
     * 批次备注
     */
    private String batchRemark;

    /**
     * 单条转账备注（微信用户收款通知展示）
     */
    private String transferRemark;

    /**
     * 转账金额（元，必须大于 0）
     */
    private BigDecimal amount;

    /**
     * 收款用户 openid
     */
    private String openid;

    /**
     * 收款用户真实姓名（内存真明文，可选；明细金额 >= 2000 元时微信强制必填）
     */
    private String userName;

    /**
     * 转账场景 ID（可选，如 1001-现金营销）
     */
    private String transferSceneId;

    /**
     * 异步结果通知地址（可选）
     */
    private String notifyUrl;

    @Override
    public String toString() {
        return "WechatTransferRequest{" +
                "merchantKey='" + merchantKey + '\'' +
                ", outBatchNo='" + outBatchNo + '\'' +
                ", outDetailNo='" + outDetailNo + '\'' +
                ", batchName='" + batchName + '\'' +
                ", batchRemark='" + batchRemark + '\'' +
                ", transferRemark='" + transferRemark + '\'' +
                ", amount=" + amount +
                ", openid='" + DesensitizeUtil.maskKeepHeadTail(openid, 3, 3) + '\'' +
                ", userName='" + DesensitizeUtil.chineseName(userName) + '\'' +
                ", transferSceneId='" + transferSceneId + '\'' +
                ", notifyUrl='" + notifyUrl + '\'' +
                '}';
    }
}
