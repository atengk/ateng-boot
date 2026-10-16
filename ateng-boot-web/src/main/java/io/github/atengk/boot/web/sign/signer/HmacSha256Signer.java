package io.github.atengk.boot.web.sign.signer;

import io.github.atengk.boot.web.sign.spi.ApiSigner;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 基于纯 JDK 原生 HmacSHA256 算法的对称数字签名器
 * 运算速度达纳秒级，零外部重型依赖，适用于移动端及微服务间通信
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class HmacSha256Signer implements ApiSigner {

    private static final String ALGORITHM = "HmacSHA256";

    @Override
    public String sign(String data, String secretKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(secretKey)) {
            throw new IllegalArgumentException("待签名数据与对称密钥不能为空");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] signBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 签名计算失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String secretKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(secretKey)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] expectedBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            String expectedBase64 = Base64.getEncoder().encodeToString(expectedBytes);
            if (MessageDigest.isEqual(expectedBase64.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }

            // 兼容客户端传入十六进制小写或大写形式
            String expectedHex = HexFormat.of().formatHex(expectedBytes);
            return MessageDigest.isEqual(expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
