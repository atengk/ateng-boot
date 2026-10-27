package io.github.atengk.boot.web.sign;

import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 国密 SM2/SM3 签名算法器核心与对抗性单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("国密 SM2/SM3 签名算法器核心与对抗性单元测试")
class Sm2SignerTest {

    private final Sm2Signer signer = new Sm2Signer();

    @Test
    @DisplayName("SM2: 密钥生成、标准加签验签与数据篡改防御")
    void testSignAndVerifyHappyPath() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();
        assertThat(keyPair.privateKeyHex()).hasSize(64);
        assertThat(keyPair.publicKeyHex()).hasSize(130).startsWith("04");

        String data = "POST\n/api/orders\n1729000000000\nnonce-sm2-123\n{\"id\":1001,\"name\":\"信创订单\"}\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // 1. 使用 Hex 格式私钥加签
        String signature = signer.sign(data, keyPair.privateKeyHex());
        assertThat(signature).isNotBlank();

        // 2. 使用 Hex 格式公钥验签
        boolean verifyResult = signer.verify(data, signature, keyPair.publicKeyHex());
        assertThat(verifyResult).isTrue();

        // 3. 篡改数据验签失败
        assertThat(signer.verify(data + "tampered", signature, keyPair.publicKeyHex())).isFalse();

        // 4. 篡改签名串验签失败
        String tamperedSig = signature.substring(0, signature.length() - 4) + "AAAA";
        assertThat(signer.verify(data, tamperedSig, keyPair.publicKeyHex())).isFalse();

        // 5. 换用其他公钥验签失败
        Sm2Signer.Sm2KeyPair anotherPair = Sm2Signer.generateKeyPair();
        assertThat(signer.verify(data, signature, anotherPair.publicKeyHex())).isFalse();
    }

    @Test
    @DisplayName("SM2: 兼容 Base64 格式公私钥与 PEM 头尾包裹")
    void testBase64AndPemFormatKeys() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();

        String privateKeyPem = "-----BEGIN EC PRIVATE KEY-----\n"
                + keyPair.privateKeyBase64()
                + "\n-----END EC PRIVATE KEY-----";
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + keyPair.publicKeyBase64()
                + "\n-----END PUBLIC KEY-----";

        String data = "GET\n/api/user/info\n1729000000000\nnonce-456\nuid=9988";

        // 1. PEM 私钥加签
        String signature = signer.sign(data, privateKeyPem);
        assertThat(signature).isNotBlank();

        // 2. PEM 公钥验签
        assertThat(signer.verify(data, signature, publicKeyPem)).isTrue();

        // 3. 裸 Base64 私钥加签与公钥验签
        String sig2 = signer.sign(data, keyPair.privateKeyBase64());
        assertThat(signer.verify(data, sig2, keyPair.publicKeyBase64())).isTrue();
    }

    @Test
    @DisplayName("SM2: 支持 128 位裸公钥与 66 位压缩公钥验签")
    void testRawAndCompressedPublicKey() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();
        String data = "data-to-sign";

        String sig = signer.sign(data, keyPair.privateKeyHex());

        // 128 位裸 X + Y (去掉 04 前缀)
        String rawPubKey128 = keyPair.publicKeyHex().substring(2);
        assertThat(rawPubKey128).hasSize(128);
        assertThat(signer.verify(data, sig, rawPubKey128)).isTrue();

        // 66 位压缩公钥
        byte[] pubBytes = HexFormat.of().parseHex(keyPair.publicKeyHex());
        int yTilde = pubBytes[64] & 1;
        String compPrefix = (yTilde == 1) ? "03" : "02";
        String compPubKey66 = compPrefix + keyPair.publicKeyHex().substring(2, 66);
        assertThat(compPubKey66).hasSize(66);
        assertThat(signer.verify(data, sig, compPubKey66)).isTrue();
    }

    @Test
    @DisplayName("SM2: 支持裸 64 字节 r || s 格式签名验签")
    void testRaw64ByteSignatureVerify() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();
        String data = "test-raw-signature";
        String derSig = signer.sign(data, keyPair.privateKeyHex());

        // 解析 DER 提取 64 字节 r || s
        byte[] derBytes = Base64.getDecoder().decode(derSig);
        int pos = 1;
        int seqLen = derBytes[pos++] & 0xFF;
        if ((seqLen & 0x80) != 0) {
            pos += (seqLen & 0x7F);
        }
        pos++; // skip 0x02
        int rLen = derBytes[pos++] & 0xFF;
        byte[] rb = new byte[32];
        if (rLen > 32) {
            System.arraycopy(derBytes, pos + (rLen - 32), rb, 0, 32);
        } else {
            System.arraycopy(derBytes, pos, rb, 32 - rLen, rLen);
        }
        pos += rLen;

        pos++; // skip 0x02
        int sLen = derBytes[pos++] & 0xFF;
        byte[] sb = new byte[32];
        if (sLen > 32) {
            System.arraycopy(derBytes, pos + (sLen - 32), sb, 0, 32);
        } else {
            System.arraycopy(derBytes, pos, sb, 32 - sLen, sLen);
        }

        byte[] raw64 = new byte[64];
        System.arraycopy(rb, 0, raw64, 0, 32);
        System.arraycopy(sb, 0, raw64, 32, 32);
        String rawSigBase64 = Base64.getEncoder().encodeToString(raw64);

        assertThat(signer.verify(data, rawSigBase64, keyPair.publicKeyHex())).isTrue();
    }

    @Test
    @DisplayName("SM2: 自定义用户标识 (UserId) 隔离性验证")
    void testCustomUserId() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();
        String data = "payload-identity";

        Sm2Signer signerA = new Sm2Signer("USER_ALPHA");
        Sm2Signer signerB = new Sm2Signer("USER_BETA");

        String sigA = signerA.sign(data, keyPair.privateKeyHex());

        // 相同 UserId 验签成功
        assertThat(signerA.verify(data, sigA, keyPair.publicKeyHex())).isTrue();

        // 不同 UserId 验签失败
        assertThat(signerB.verify(data, sigA, keyPair.publicKeyHex())).isFalse();
    }

    @Test
    @DisplayName("SM2: 边界与异常入参前置卫语句防卫")
    void testDefensiveEdgeCases() {
        Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();

        // 1. 空入参防卫
        assertThatThrownBy(() -> signer.sign(null, keyPair.privateKeyHex()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign("data", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign("data", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(signer.verify(null, "sig", keyPair.publicKeyHex())).isFalse();
        assertThat(signer.verify("data", null, keyPair.publicKeyHex())).isFalse();
        assertThat(signer.verify("data", "sig", null)).isFalse();

        // 2. 非法私钥格式
        assertThatThrownBy(() -> signer.sign("data", "invalid-key-hex"))
                .isInstanceOf(IllegalArgumentException.class);

        // 3. 非法公钥格式
        assertThat(signer.verify("data", "sig", "not-a-valid-pubkey")).isFalse();
    }
}
