package io.github.atengk.boot.web.crypto.provider;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 默认加解密密钥提供者单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("默认加解密密钥提供者单元测试")
class DefaultApiCryptoKeyProviderTest {

    @Test
    @DisplayName("单版本配置支持多算法专属密钥路由与未知 keyId 拦截")
    void shouldRoutePerAlgorithmAndRejectUnknownKeyId() {
        WebProperties.CryptoProperties props = new WebProperties.CryptoProperties();
        props.setActiveKeyId("v1");
        props.setRsaServerPrivateKey("rsa-srv-priv");
        props.setRsaClientPublicKey("rsa-cli-pub");
        props.setSm2ServerPrivateKey("sm2-srv-priv");
        props.setSm2ClientPublicKey("sm2-cli-pub");

        DefaultApiCryptoKeyProvider provider = new DefaultApiCryptoKeyProvider(props);
        assertThat(provider.getActiveKeyId("default", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("v1");
        assertThat(provider.getServerPrivateKey("default", "v1", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("rsa-srv-priv");
        assertThat(provider.getClientPublicKey("default", null, CryptoAlgorithm.SM2_SM4_GCM)).isEqualTo("sm2-cli-pub");

        assertThatThrownBy(() -> provider.getServerPrivateKey("default", "v99", CryptoAlgorithm.RSA_AES_GCM))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("多版本 keys 配置支持平滑密钥轮换、历史版本共存与停用版本快速拒绝")
    void shouldSupportKeyRotationAndRejectDisabledOrUnknownKeyId() {
        WebProperties.CryptoProperties props = new WebProperties.CryptoProperties();
        props.setActiveKeyId("v2");

        WebProperties.CryptoKeyVersionProperties v0 = new WebProperties.CryptoKeyVersionProperties();
        v0.setEnabled(false);
        v0.setRsaServerPrivateKey("v0-priv");

        WebProperties.CryptoKeyVersionProperties v1 = new WebProperties.CryptoKeyVersionProperties();
        v1.setEnabled(true);
        v1.setRsaServerPrivateKey("v1-priv");
        v1.setRsaClientPublicKey("v1-pub");

        WebProperties.CryptoKeyVersionProperties v2 = new WebProperties.CryptoKeyVersionProperties();
        v2.setEnabled(true);
        v2.setRsaServerPrivateKey("v2-priv");
        v2.setRsaClientPublicKey("v2-pub");

        props.getKeys().put("v0", v0);
        props.getKeys().put("v1", v1);
        props.getKeys().put("v2", v2);

        DefaultApiCryptoKeyProvider provider = new DefaultApiCryptoKeyProvider(props);

        // 出站使用当前活跃版本 v2
        assertThat(provider.getActiveKeyId("app", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("v2");
        assertThat(provider.getClientPublicKey("app", "v2", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("v2-pub");

        // 轮换过渡期历史启用版本 v1 与新版本 v2 均可正常解密
        assertThat(provider.getServerPrivateKey("app", "v1", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("v1-priv");
        assertThat(provider.getServerPrivateKey("app", "v2", CryptoAlgorithm.RSA_AES_GCM)).isEqualTo("v2-priv");

        // 已停用/过期版本 v0 被拦截
        assertThatThrownBy(() -> provider.getServerPrivateKey("app", "v0", CryptoAlgorithm.RSA_AES_GCM))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode())
                .hasMessageContaining("过期或停用");

        // 未知版本 unknown-key 被拦截
        assertThatThrownBy(() -> provider.getServerPrivateKey("app", "unknown-key", CryptoAlgorithm.RSA_AES_GCM))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", CommonResultCode.BAD_REQUEST.getCode())
                .hasMessageContaining("未知的加密密钥版本");
    }
}
