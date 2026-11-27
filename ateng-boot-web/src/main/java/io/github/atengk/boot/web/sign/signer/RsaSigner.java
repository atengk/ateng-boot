package io.github.atengk.boot.web.sign.signer;

import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import org.springframework.util.StringUtils;

/**
 * 基于纯 JDK 原生 RSA (SHA256withRSA) 算法的非对称数字签名器
 * 严格分离公钥验签与私钥加签，底层密码学原语统一委托至 {@link RsaUtil}，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RsaSigner implements ApiSigner {

    @Override
    public String sign(String data, String privateKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(privateKeyPem)) {
            throw new IllegalArgumentException("待签名数据与 RSA 私钥不能为空");
        }
        try {
            return RsaUtil.signSha256Base64(data, privateKeyPem);
        } catch (Exception e) {
            throw new IllegalStateException("RSA 数据加签失败", e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String publicKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(publicKeyPem)) {
            return false;
        }
        return RsaUtil.verifySha256Base64(data, signature, publicKeyPem);
    }
}
