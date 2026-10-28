package io.github.atengk.boot.datasource.security;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源初始化事件与零明文防卫测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@DisplayName("数据源初始化事件与零明文防卫测试")
class AtengDataSourceInitEventTest {

    @Test
    @DisplayName("验证在创建期间瞬态解密为明文，创建完毕后立即还原密文，绝不持久化明文凭据")
    void shouldTransientlyDecryptAndImmediatelyRestoreCiphertext() {
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String rawPassword = "DbRootPassword123#";
        String ciphertext = CryptoUtil.encryptSm4Ecb(rawPassword, key);
        String encryptedPassword = "enc:sm4:" + CryptoUtil.encodeBase64(key) + ":" + ciphertext;

        DatasourceProperties properties = new DatasourceProperties();
        properties.getSecurity().setPasswordDecryptionEnabled(true);

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(properties);
        AtengDataSourceInitEvent initEvent = new AtengDataSourceInitEvent(decryptor, properties);

        DataSourceProperty dsProperty = new DataSourceProperty();
        dsProperty.setPoolName("order_ds");
        dsProperty.setPassword(encryptedPassword);

        // 1. beforeCreate: 应该瞬态解密为明文供连接池握手
        initEvent.beforeCreate(dsProperty);
        assertThat(dsProperty.getPassword()).isEqualTo(rawPassword);

        // 2. afterCreate: 连接池创建完成，应该立即还原为原始密文
        DataSource mockDs = Mockito.mock(DataSource.class);
        initEvent.afterCreate(mockDs);
        assertThat(dsProperty.getPassword()).isEqualTo(encryptedPassword);
    }

    @Test
    @DisplayName("测试当显式禁用密码解密时，密文不被解密")
    void shouldBypassWhenPasswordDecryptionDisabled() {
        String encryptedPassword = "enc:sm4:someKey:someCipher";

        DatasourceProperties properties = new DatasourceProperties();
        properties.getSecurity().setPasswordDecryptionEnabled(false);

        CryptoUtilPasswordDecryptor decryptor = new CryptoUtilPasswordDecryptor(properties);
        AtengDataSourceInitEvent initEvent = new AtengDataSourceInitEvent(decryptor, properties);

        DataSourceProperty dsProperty = new DataSourceProperty();
        dsProperty.setPassword(encryptedPassword);

        initEvent.beforeCreate(dsProperty);
        assertThat(dsProperty.getPassword()).isEqualTo(encryptedPassword);

        DataSource mockDs = Mockito.mock(DataSource.class);
        initEvent.afterCreate(mockDs);
        assertThat(dsProperty.getPassword()).isEqualTo(encryptedPassword);
    }
}
