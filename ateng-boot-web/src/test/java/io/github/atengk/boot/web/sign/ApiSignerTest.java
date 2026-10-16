package io.github.atengk.boot.web.sign;

import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 签名算法器核心单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("纯 JDK 签名算法器核心单元测试")
class ApiSignerTest {

    @Test
    @DisplayName("HmacSha256Signer: 正常加签、验签与篡改防御验证")
    void testHmacSha256Signer() {
        HmacSha256Signer signer = new HmacSha256Signer();
        String secretKey = "MySuperSecretKey123456";
        String data = "POST\n/api/orders\n1729000000\nnonce123\nname=test\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // 1. 生成签名
        String signature = signer.sign(data, secretKey);
        assertThat(signature).isNotBlank();

        // 2. 正常验签
        assertThat(signer.verify(data, signature, secretKey)).isTrue();

        // 3. 篡改数据验签失败
        assertThat(signer.verify(data + "tampered", signature, secretKey)).isFalse();

        // 4. 错误密钥验签失败
        assertThat(signer.verify(data, signature, "WrongKey9999")).isFalse();

        // 5. 空值防御
        assertThatThrownBy(() -> signer.sign(null, secretKey)).isInstanceOf(IllegalArgumentException.class);
        assertThat(signer.verify(null, signature, secretKey)).isFalse();
        assertThat(signer.verify(data, null, secretKey)).isFalse();
    }

    @Test
    @DisplayName("RsaSigner: 非对称公私钥加签、验签与防伪验证")
    void testRsaSigner() throws Exception {
        // 生成测试用 2048 位 RSA 密钥对
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        RsaSigner signer = new RsaSigner();
        String data = "GET\n/api/users/page\n1729000000\nnonce456\ncurrent=1&size=10\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // 1. 私钥加签
        String signature = signer.sign(data, privateKeyPem);
        assertThat(signature).isNotBlank();

        // 2. 公钥验签
        assertThat(signer.verify(data, signature, publicKeyPem)).isTrue();

        // 3. 篡改数据验签失败
        assertThat(signer.verify(data + "tampered", signature, publicKeyPem)).isFalse();

        // 4. 生成另一对密钥，用不同公钥验签失败
        KeyPair anotherPair = generator.generateKeyPair();
        String anotherPublic = Base64.getEncoder().encodeToString(anotherPair.getPublic().getEncoded());
        assertThat(signer.verify(data, signature, anotherPublic)).isFalse();
    }
}
