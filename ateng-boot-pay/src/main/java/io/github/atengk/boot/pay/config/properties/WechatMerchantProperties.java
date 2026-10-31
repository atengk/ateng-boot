package io.github.atengk.boot.pay.config.properties;

import lombok.Data;

import java.io.Serializable;

/**
 * 微信支付单商户配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class WechatMerchantProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 微信公众平台 / 开放平台关联的应用 AppId
     */
    private String appId;

    /**
     * 微信支付直连商户号 (MchId)
     */
    private String mchId;

    /**
     * APIv3 密钥 (32 字节高强度密钥，用于回调解密与出站认证)
     */
    private String apiV3Key;

    /**
     * 商户 API 证书序列号
     */
    private String merchantSerialNumber;

    /**
     * 商户私钥文件路径，支持 file: 物理绝对路径或 classpath: 类路径资源
     */
    private String privateKeyPath;

    /**
     * 商户私钥 PEM 纯文本内容（适合云原生容器通过环境变量或 ConfigMap 注入）
     */
    private String privateKeyPem;

    /**
     * 异步通知回调地址 (Webhook URL)
     */
    private String notifyUrl;

    /**
     * 是否启用平台证书自动定时下载与内存平滑轮换机制 (默认 true)
     */
    private boolean autoCertificate = true;

    /**
     * 微信支付平台公钥内容 (配置后优先使用静态公钥验签，微信新版公钥模式)
     */
    private String wechatPayPublicKey;

    /**
     * 微信支付平台公钥文件路径，支持 file: 物理绝对路径或 classpath: 类路径资源（适合 Kubernetes Secret 挂载或集中配置文件）
     */
    private String wechatPayPublicKeyPath;

    /**
     * 微信支付平台公钥 ID
     */
    private String wechatPayPublicKeyId;

    /**
     * 解析微信支付平台公钥配置源（优先使用行内文本，其次使用路径）
     *
     * @return 公钥源文本或路径，若未配置返回 null
     */
    public String resolvePublicKeySource() {
        if (wechatPayPublicKey != null && !wechatPayPublicKey.isBlank()) {
            return wechatPayPublicKey.trim();
        }
        if (wechatPayPublicKeyPath != null && !wechatPayPublicKeyPath.isBlank()) {
            return wechatPayPublicKeyPath.trim();
        }
        return null;
    }

    /**
     * 是否配置了微信支付平台公钥
     *
     * @return true 若配置了公钥文本或公钥路径
     */
    public boolean hasPublicKey() {
        return resolvePublicKeySource() != null;
    }

    /**
     * 服务商商户号 (服务商模式专用)
     */
    private String spMchId;

    /**
     * 服务商应用 AppId (服务商模式专用)
     */
    private String spAppId;

    /**
     * 特约子商户号 (服务商模式专用，sub_mchid)
     */
    private String subMchId;

    /**
     * 特约子商户应用 AppId (服务商模式可选，sub_appid)
     */
    private String subAppId;
}
