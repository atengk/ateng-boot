package io.github.atengk.boot.pay.spi;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 支付证书与私钥外部加载 SPI 接口
 * <p>
 * 允许业务方、配置中心、数据库或企业 KMS / HashiCorp Vault 动态提供商户私钥与证书。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface PayCertificateProvider {

    /**
     * 判断当前提供者是否支持加载该位置或标识符对应的证书/私钥
     *
     * @param locationOrIdentifier 路径、资源定位符或自定义凭据标识 (如 kms:mch-001, db:cert-1002)
     * @return true 表示支持处理
     */
    boolean supports(String locationOrIdentifier);

    /**
     * 加载凭据字节数组
     *
     * @param locationOrIdentifier 凭据标识符
     * @return 原始文件或证书字节数组，若无则返回 null
     */
    byte[] loadBytes(String locationOrIdentifier);

    /**
     * 加载凭据输入流 (默认将 loadBytes 包装为 InputStream)
     *
     * @param locationOrIdentifier 凭据标识符
     * @return 证书或私钥流
     */
    default InputStream loadInputStream(String locationOrIdentifier) {
        byte[] bytes = loadBytes(locationOrIdentifier);
        return bytes != null ? new ByteArrayInputStream(bytes) : null;
    }

    /**
     * 加载凭据纯文本 PEM 字符串
     *
     * @param locationOrIdentifier 凭据标识符
     * @return 证书或私钥 PEM 字符串
     */
    default String loadPem(String locationOrIdentifier) {
        byte[] bytes = loadBytes(locationOrIdentifier);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }
}
