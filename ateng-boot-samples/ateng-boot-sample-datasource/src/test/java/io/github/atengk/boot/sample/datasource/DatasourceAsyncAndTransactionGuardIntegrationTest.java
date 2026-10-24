package io.github.atengk.boot.sample.datasource;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.security.DataSourcePasswordDecryptor;
import io.github.atengk.boot.sample.datasource.service.DatasourceAsyncContextScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceTransactionGuardScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事务守卫、异步上下文透传与凭据解密端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
class DatasourceAsyncAndTransactionGuardIntegrationTest {

    @Autowired
    private DatasourceTransactionGuardScenarioService txGuardService;

    @Autowired
    private DatasourceAsyncContextScenarioService asyncContextService;

    @Autowired
    private DataSourcePasswordDecryptor passwordDecryptor;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("事务守卫刚性拦截: @Transactional 事务中尝试跨物理连接切源，立即被 DynamicDatasourceGuard 拦截")
    void testTransactionGuardInterceptsFalseSwitch() {
        assertThatThrownBy(() ->
                txGuardService.transactionWithIllegalSwitch("ORD-TX-M-01", "ORD-TX-T-01")
        ).isInstanceOf(DataSourceSwitchException.class)
         .hasMessageContaining("假切源致命风险");
    }

    @Test
    @DisplayName("事务守卫同源放行: @Transactional 事务中切源至相同物理数据源，安全放行")
    void testTransactionGuardAllowsSameDataSource() {
        // 同源重入应正常提交，不应抛出异常
        txGuardService.transactionWithSameDsReentrant("ORD-TX-SAME-01");
    }

    @Test
    @DisplayName("事务守卫独立新事务放行: @Transactional(propagation = Propagation.REQUIRES_NEW) 挂起外层事务切源放行")
    void testTransactionGuardAllowsRequiresNew() {
        txGuardService.transactionWithSafeRequiresNew("ORD-OUTER-01", "ORD-INNER-01");
    }

    @Test
    @DisplayName("异步上下文透传: AsyncContext 自动将当前线程绑定的数据源与租户上下文透传至异步子线程")
    void testAsyncContextPropagation() throws Exception {
        TenantContextHolder.setTenantId("1001");
        CompletableFuture<String> future = asyncContextService.executeAsyncTaskWithContext();
        String result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("凭据安全解密: DataSourcePasswordDecryptor 支持 enc:sm4: 与 enc:aes: 密文格式探测与安全解密")
    void testPasswordDecryption() {
        // 1. 普通明文探测为 false
        assertThat(passwordDecryptor.supports("plain-password-123")).isFalse();

        // 2. SM4 密文探测为 true 并成功解密
        String rawPassword = "mySecretDbPass_2026";
        byte[] sm4Key = io.github.atengk.boot.core.util.CryptoUtil.generateKey(128);
        String sm4Ciphertext = io.github.atengk.boot.core.util.CryptoUtil.encryptSm4Ecb(rawPassword, sm4Key);
        String base64Sm4Key = io.github.atengk.boot.core.util.CryptoUtil.encodeBase64(sm4Key);
        String sm4Encrypted = "enc:sm4:" + base64Sm4Key + ":" + sm4Ciphertext;

        assertThat(passwordDecryptor.supports(sm4Encrypted)).isTrue();
        String decryptedSm4 = passwordDecryptor.decrypt(sm4Encrypted);
        assertThat(decryptedSm4).isEqualTo(rawPassword);

        // 3. AES 密文探测为 true 并成功解密
        byte[] aesKey = io.github.atengk.boot.core.util.CryptoUtil.generateKey(128);
        byte[] aesIv = io.github.atengk.boot.core.util.CryptoUtil.generateIv();
        String aesCiphertext = io.github.atengk.boot.core.util.CryptoUtil.encryptCbc(rawPassword, aesKey, aesIv);
        String base64AesKey = io.github.atengk.boot.core.util.CryptoUtil.encodeBase64(aesKey);
        String base64AesIv = io.github.atengk.boot.core.util.CryptoUtil.encodeBase64(aesIv);
        String aesEncrypted = "enc:aes:" + base64AesKey + ":" + base64AesIv + ":" + aesCiphertext;

        assertThat(passwordDecryptor.supports(aesEncrypted)).isTrue();
        String decryptedAes = passwordDecryptor.decrypt(aesEncrypted);
        assertThat(decryptedAes).isEqualTo(rawPassword);
    }
}
