package io.github.atengk.boot.datasource.security;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯内存数据库密码安全解密器单测
 *
 * @author Ateng
 * @since 2026-10-24
 */
@DisplayName("纯内存数据库密码安全解密器单测")
class CryptoUtilPasswordDecryptorTest {

    @Test
    @DisplayName("测试国密 SM4/CBC 密文密码纯内存解密（自包含密钥与 IV）")
    void shouldDecryptSm4CbcWithSelfContainedKeyAndIv() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = "fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        String rawPassword = "Db_Secret_Pass_2026!";

        String ciphertext = CryptoUtil.encryptSm4Cbc(rawPassword, key, iv);
        String base64Key = CryptoUtil.encodeBase64(key);
        String base64Iv = CryptoUtil.encodeBase64(iv);
        String encryptedPayload = "enc:sm4:" + base64Key + ":" + base64Iv + ":" + ciphertext;

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(new DatasourceProperties());
        assertThat(decryptor.supports(encryptedPayload)).isTrue();

        String decrypted = decryptor.decrypt(encryptedPayload);
        assertThat(decrypted).isEqualTo(rawPassword);
    }

    @Test
    @DisplayName("测试国密 SM4/ECB 密文密码纯内存解密（自包含密钥）")
    void shouldDecryptSm4EcbWithSelfContainedKey() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String rawPassword = "Ecb_Secret_Pass_888!";

        String ciphertext = CryptoUtil.encryptSm4Ecb(rawPassword, key);
        String base64Key = CryptoUtil.encodeBase64(key);
        String encryptedPayload = "enc:sm4:" + base64Key + ":" + ciphertext;

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(new DatasourceProperties());
        assertThat(decryptor.supports(encryptedPayload)).isTrue();

        String decrypted = decryptor.decrypt(encryptedPayload);
        assertThat(decrypted).isEqualTo(rawPassword);
    }

    @Test
    @DisplayName("测试国际 AES/CBC 密文密码纯内存解密（自包含密钥与 IV）")
    void shouldDecryptAesCbcWithSelfContainedKeyAndIv() {
        byte[] key = CryptoUtil.generateKey(128);
        byte[] iv = CryptoUtil.generateIv();
        String rawPassword = "Aes_Secret_Password_999@";

        String ciphertext = CryptoUtil.encryptCbc(rawPassword, key, iv);
        String base64Key = CryptoUtil.encodeBase64(key);
        String base64Iv = CryptoUtil.encodeBase64(iv);
        String encryptedPayload = "enc:aes:" + base64Key + ":" + base64Iv + ":" + ciphertext;

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(new DatasourceProperties());
        assertThat(decryptor.supports(encryptedPayload)).isTrue();

        String decrypted = decryptor.decrypt(encryptedPayload);
        assertThat(decrypted).isEqualTo(rawPassword);
    }

    @Test
    @DisplayName("测试使用全局配置密钥解密单段密文密码")
    void shouldDecryptWithGlobalSecretKey() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String rawPassword = "Global_Secret_Pass_666!";
        String ciphertext = CryptoUtil.encryptSm4Ecb(rawPassword, key);

        DatasourceProperties properties = new DatasourceProperties();
        properties.getSecurity().setSecretKey(CryptoUtil.encodeBase64(key));

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(properties);
        String encryptedPayload = "enc:sm4:" + ciphertext;

        String decrypted = decryptor.decrypt(encryptedPayload);
        assertThat(decrypted).isEqualTo(rawPassword);
    }

    @Test
    @DisplayName("测试未加密密码透传与非法密文异常防御")
    void shouldBypassUnencryptedAndFailFastOnIllegalPayload() {
        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(new DatasourceProperties());

        // 明文原样返回
        assertThat(decryptor.supports("plainPassword123")).isFalse();
        assertThat(decryptor.decrypt("plainPassword123")).isEqualTo("plainPassword123");
        assertThat(decryptor.decrypt(null)).isNull();

        // 格式非法阻断
        assertThatThrownBy(() -> decryptor.decrypt("enc:aes:invalidFormat"))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("未配置全局解密密钥");

        assertThatThrownBy(() -> decryptor.decrypt("enc:sm4:a:b:c:d"))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("非法 SM4 密文格式");
    }
}
