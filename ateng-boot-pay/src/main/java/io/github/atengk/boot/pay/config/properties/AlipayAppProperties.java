package io.github.atengk.boot.pay.config.properties;

import lombok.Data;

import java.io.Serializable;

/**
 * 支付宝单应用配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class AlipayAppProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付宝开放平台分配的应用 AppId
     */
    private String appId;

    /**
     * 商户应用私钥内容（支持 PKCS8 格式文本或 PEM 格式字符串）
     */
    private String privateKey;

    /**
     * 商户应用私钥文件路径，支持 file: 或 classpath:
     */
    private String privateKeyPath;

    /**
     * 支付宝公钥（普通公钥模式下使用）
     */
    private String alipayPublicKey;

    /**
     * 是否开启公钥证书模式（证书模式下使用 appCertPath / alipayCertPath / alipayRootCertPath）
     */
    private boolean certMode = false;

    /**
     * 应用公钥证书文件路径（如 appCertPublicKey.crt）
     */
    private String appCertPath;

    /**
     * 支付宝公钥证书文件路径（如 alipayCertPublicKey_RSA2.crt）
     */
    private String alipayCertPath;

    /**
     * 支付宝根证书文件路径（如 alipayRootCert.crt）
     */
    private String alipayRootCertPath;

    /**
     * 异步通知回调地址 (Webhook URL)
     */
    private String notifyUrl;

    /**
     * 网页支付同步跳转返回地址 (Return URL)
     */
    private String returnUrl;

    /**
     * 支付宝网关地址，默认正式环境网关
     */
    private String serverUrl = "https://openapi.alipay.com/gateway.do";

    /**
     * 数据格式，默认 json
     */
    private String format = "json";

    /**
     * 字符编码集，默认 UTF-8
     */
    private String charset = "UTF-8";

    /**
     * 签名算法类型，默认 RSA2
     */
    private String signType = "RSA2";

    /**
     * 第三方应用授权令牌 (ISV 服务商模式代特约商家调用专用，app_auth_token)
     */
    private String appAuthToken;
}
