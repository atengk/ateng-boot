package io.github.atengk.boot.pay.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信小程序 / 公众号网页 JSAPI 调起支付参数 (5 元素直接透传至 wx.requestPayment)
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatJsapiParams implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 ID (appId)
     */
    private String appId;

    /**
     * 时间戳 (秒级时间戳字符串，timeStamp)
     */
    private String timeStamp;

    /**
     * 随机字符串 (nonceStr)
     */
    private String nonceStr;

    /**
     * 统一下单接口返回的 prepay_id 参数值，格式为 prepay_id=***
     */
    @JsonProperty("package")
    private String packageVal;

    /**
     * 签名方式 (默认 RSA)
     */
    private String signType;

    /**
     * 签名值 (使用商户私钥计算的 SHA256withRSA 二次签名)
     */
    private String paySign;

    /**
     * 兼容返回 package 字段别名
     */
    public String getPackage() {
        return packageVal;
    }

    /**
     * 兼容设置 package 字段别名
     */
    public void setPackage(String packageVal) {
        this.packageVal = packageVal;
    }
}
