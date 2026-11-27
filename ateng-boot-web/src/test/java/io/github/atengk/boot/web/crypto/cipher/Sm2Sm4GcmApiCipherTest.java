package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 国密 SM2 + SM4-GCM 混合加解密引擎单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("国密 SM2 + SM4-GCM 混合加解密引擎单元测试")
class Sm2Sm4GcmApiCipherTest {

    private static final Sm2Signer.Sm2KeyPair SM2_KEY_PAIR = Sm2Signer.generateKeyPair();
    private final Sm2Sm4GcmApiCipher cipher = new Sm2Sm4GcmApiCipher();

    @Test
    @DisplayName("支持 SM2_SM4_GCM 算法并完成 Hex/Base64 密钥及 AAD 双向混合加解密闭环")
    void shouldEncryptAndDecryptSm2Sm4Gcm() {
        assertThat(cipher.supports(CryptoAlgorithm.SM2_SM4_GCM)).isTrue();
        assertThat(cipher.supports(CryptoAlgorithm.RSA_AES_GCM)).isFalse();

        byte[] plainBytes = "{\"orderNo\":\"ORD-GM-001\",\"note\":\"国密传输测试\"}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = ApiCryptoContext.buildStandardAad("gm-app", "v1", CryptoAlgorithm.SM2_SM4_GCM);

        ApiCipherResult enc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.publicKeyHex(), aad));

        byte[] decHex = cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.privateKeyHex(), aad)
        );
        assertThat(decHex).isEqualTo(plainBytes);

        // Base64 格式公私钥兼容测试
        ApiCipherResult encB64 = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.publicKeyBase64(), null));
        byte[] decB64 = cipher.decrypt(
                new ApiCipherInput(encB64.encryptedKey(), encB64.iv(), encB64.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.privateKeyBase64(), null)
        );
        assertThat(decB64).isEqualTo(plainBytes);
    }

    @Test
    @DisplayName("兼容 ASN.1 DER 编码的 SM2 会话密钥封装格式解密")
    void shouldDecryptAsn1DerWrappedSm2Key() {
        byte[] plainBytes = "{\"channel\":\"FINANCE_GM\"}".getBytes(StandardCharsets.UTF_8);
        ApiCipherResult enc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.publicKeyHex(), null));

        byte[] c1c3c2 = Base64.getDecoder().decode(enc.encryptedKey());
        byte[] asn1Der = toAsn1Der(c1c3c2);
        String derBase64 = Base64.getEncoder().encodeToString(asn1Der);

        byte[] decrypted = cipher.decrypt(
                new ApiCipherInput(derBase64, enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.privateKeyHex(), null)
        );
        assertThat(decrypted).isEqualTo(plainBytes);
    }

    @Test
    @DisplayName("SM4-GCM Tag 篡改、SM2 C3 篡改与 AAD 不匹配抛出 400 BusinessException")
    void shouldRejectTamperedCiphertextAndInvalidKeys() {
        byte[] plainBytes = "{\"amount\":500}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = ApiCryptoContext.buildStandardAad("gm-app", "v1", CryptoAlgorithm.SM2_SM4_GCM);
        ApiCipherResult enc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.publicKeyHex(), aad));

        // 1. SM4-GCM Tag 篡改
        byte[] cipherRaw = Base64.getDecoder().decode(enc.cipherText());
        cipherRaw[cipherRaw.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), Base64.getEncoder().encodeToString(cipherRaw)),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.privateKeyHex(), aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode())
                .hasCauseInstanceOf(javax.crypto.AEADBadTagException.class);

        // 2. SM2 C3 摘要篡改
        byte[] wrappedRaw = Base64.getDecoder().decode(enc.encryptedKey());
        wrappedRaw[70] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(Base64.getEncoder().encodeToString(wrappedRaw), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "gm-app", "v1", SM2_KEY_PAIR.privateKeyHex(), aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());

        // 3. AAD 篡改
        byte[] badAad = ApiCryptoContext.buildStandardAad("other-app", "v1", CryptoAlgorithm.SM2_SM4_GCM);
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.SM2_SM4_GCM, "other-app", "v1", SM2_KEY_PAIR.privateKeyHex(), badAad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());
    }

    private static byte[] toAsn1Der(byte[] c1c3c2) {
        byte[] x1 = new byte[32];
        byte[] y1 = new byte[32];
        byte[] c3 = new byte[32];
        int c2Len = c1c3c2.length - 97;
        byte[] c2 = new byte[c2Len];
        System.arraycopy(c1c3c2, 1, x1, 0, 32);
        System.arraycopy(c1c3c2, 33, y1, 0, 32);
        System.arraycopy(c1c3c2, 65, c3, 0, 32);
        System.arraycopy(c1c3c2, 97, c2, 0, c2Len);

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writeDerInt(inner, new BigInteger(1, x1));
        writeDerInt(inner, new BigInteger(1, y1));
        writeDerOctet(inner, c3);
        writeDerOctet(inner, c2);

        byte[] content = inner.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x30);
        writeLen(seq, content.length);
        seq.write(content, 0, content.length);
        return seq.toByteArray();
    }

    private static void writeDerInt(ByteArrayOutputStream out, BigInteger bi) {
        byte[] b = bi.toByteArray();
        out.write(0x02);
        writeLen(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeDerOctet(ByteArrayOutputStream out, byte[] b) {
        out.write(0x04);
        writeLen(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeLen(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
        } else {
            out.write(0x81);
            out.write(len);
        }
    }
}
