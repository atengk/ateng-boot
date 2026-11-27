package io.github.atengk.boot.web.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RSA-OAEP-SHA256 + AES-256-GCM 混合加解密引擎单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("RSA-OAEP-SHA256 + AES-256-GCM 混合加解密引擎单元测试")
class RsaAesGcmApiCipherTest {

    private static final String PUBLIC_KEY_BASE64;
    private static final String PRIVATE_KEY_BASE64;
    private static final String PUBLIC_KEY_PEM;
    private static final String PRIVATE_KEY_PEM;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            PUBLIC_KEY_BASE64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            PRIVATE_KEY_BASE64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" + PUBLIC_KEY_BASE64 + "\n-----END PUBLIC KEY-----";
            PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" + PRIVATE_KEY_BASE64 + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("初始化测试 RSA 密钥对失败", e);
        }
    }

    private final RsaAesGcmApiCipher cipher = new RsaAesGcmApiCipher();

    @Test
    @DisplayName("支持 RSA_AES_GCM 算法并完成 PEM/Base64 与 AAD 双向混合加解密闭环")
    void shouldEncryptAndDecryptWithOptionalAad() {
        assertThat(cipher.supports(CryptoAlgorithm.RSA_AES_GCM)).isTrue();
        assertThat(cipher.supports(CryptoAlgorithm.SM2_SM4_GCM)).isFalse();

        byte[] plainBytes = "{\"orderNo\":\"ORD-2026-001\",\"amount\":199.95}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = ApiCryptoContext.buildStandardAad("app-1", "v1", CryptoAlgorithm.RSA_AES_GCM);

        ApiCipherResult enc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PUBLIC_KEY_PEM, aad));
        assertThat(enc.encryptedKey()).isNotBlank();
        assertThat(enc.iv()).isNotBlank();
        assertThat(enc.cipherText()).isNotBlank();

        byte[] decrypted = cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_PEM, aad)
        );
        assertThat(decrypted).isEqualTo(plainBytes);

        // 无 AAD 模式下 Base64 密钥直接加解密闭环
        ApiCipherResult noAadEnc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PUBLIC_KEY_BASE64, null));
        byte[] noAadDec = cipher.decrypt(
                new ApiCipherInput(noAadEnc.encryptedKey(), noAadEnc.iv(), noAadEnc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_BASE64, null)
        );
        assertThat(noAadDec).isEqualTo(plainBytes);
    }

    @Test
    @DisplayName("密文篡改、Tag 篡改、AAD 不匹配与非法头均抛出保留因果链的 400 BusinessException")
    void shouldRejectTamperedPayloadOrInvalidInputs() {
        byte[] plainBytes = "{\"secret\":\"top\"}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = ApiCryptoContext.buildStandardAad("app-1", "v1", CryptoAlgorithm.RSA_AES_GCM);
        ApiCipherResult enc = cipher.encrypt(plainBytes,
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PUBLIC_KEY_BASE64, aad));

        // 1. 密文/Tag 篡改
        byte[] cipherRaw = Base64.getDecoder().decode(enc.cipherText());
        cipherRaw[cipherRaw.length - 1] ^= 0x01;
        String tamperedCipherText = Base64.getEncoder().encodeToString(cipherRaw);
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), tamperedCipherText),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_BASE64, aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode())
                .hasCauseInstanceOf(javax.crypto.AEADBadTagException.class);

        // 2. AAD 篡改
        byte[] evilAad = ApiCryptoContext.buildStandardAad("app-evil", "v1", CryptoAlgorithm.RSA_AES_GCM);
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-evil", "v1", PRIVATE_KEY_BASE64, evilAad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());

        // 3. 封装会话密钥篡改
        byte[] keyRaw = Base64.getDecoder().decode(enc.encryptedKey());
        keyRaw[0] ^= 0x55;
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(Base64.getEncoder().encodeToString(keyRaw), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_BASE64, aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());

        // 4. 非法 IV 长度与非法 Base64
        String shortIv = Base64.getEncoder().encodeToString(new byte[8]);
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), shortIv, enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_BASE64, aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());

        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput("not-base64!!!", enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", PRIVATE_KEY_BASE64, aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());

        // 5. 服务端缺少或配置错误密钥抛出 500
        assertThatThrownBy(() -> cipher.decrypt(
                new ApiCipherInput(enc.encryptedKey(), enc.iv(), enc.cipherText()),
                new ApiCryptoContext(CryptoAlgorithm.RSA_AES_GCM, "app-1", "v1", null, aad)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
    }
}
