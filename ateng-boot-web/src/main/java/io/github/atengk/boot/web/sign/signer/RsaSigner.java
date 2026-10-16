package io.github.atengk.boot.web.sign.signer;

import io.github.atengk.boot.web.sign.spi.ApiSigner;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 基于纯 JDK 原生 RSA (SHA256withRSA) 算法的非对称数字签名器
 * 严格分离公钥验签与私钥加签，零 Bouncy Castle 外部依赖，适用于开放平台外部生态对接
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RsaSigner implements ApiSigner {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    @Override
    public String sign(String data, String privateKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(privateKeyPem)) {
            throw new IllegalArgumentException("待签名数据与 RSA 私钥不能为空");
        }
        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyPem);
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new IllegalStateException("RSA 数据加签失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String publicKeyPem) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(publicKeyPem)) {
            return false;
        }
        try {
            PublicKey publicKey = parsePublicKey(publicKeyPem);
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signBytes = Base64.getDecoder().decode(cleanKey(signature));
            return verifier.verify(signBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private PrivateKey parsePrivateKey(String keyPem) throws Exception {
        String clean = cleanKey(keyPem);
        byte[] keyBytes = Base64.getDecoder().decode(clean);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(spec);
    }

    private PublicKey parsePublicKey(String keyPem) throws Exception {
        String clean = cleanKey(keyPem);
        byte[] keyBytes = Base64.getDecoder().decode(clean);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(spec);
    }

    private String cleanKey(String key) {
        return key.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }
}
