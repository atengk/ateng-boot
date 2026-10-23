package io.github.atengk.boot.pay.model.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信 App 客户端调起支付参数 (直接传递给 iOS/Android 微信开放平台 SDK)
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatAppParams implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 ID (appid)
     */
    private String appid;

    /**
     * 商户号 (partnerid)
     */
    private String partnerid;

    /**
     * 预支付交易会话 ID (prepayid)
     */
    private String prepayid;

    /**
     * 扩展字段 (固定填 Sign=WXPay)
     */
    @JsonProperty("package")
    private String packageVal;

    /**
     * 随机字符串 (noncestr)
     */
    private String noncestr;

    /**
     * 时间戳 (秒级时间戳字符串，timestamp)
     */
    private String timestamp;

    /**
     * 签名值 (sign)
     */
    private String sign;

    /**
     * 兼容返回 package 字段别名 (忽略 Jackson 避免与 packageVal 重复冲突)
     */
    @JsonIgnore
    public String getPackage() {
        return packageVal;
    }

    /**
     * 兼容设置 package 字段别名 (忽略 Jackson 避免与 packageVal 重复冲突)
     */
    @JsonIgnore
    public void setPackage(String packageVal) {
        this.packageVal = packageVal;
    }
}
