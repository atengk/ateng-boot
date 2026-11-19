package io.github.atengk.boot.sample.datasource.service;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.datasource.security.CryptoUtilPasswordDecryptor;
import io.github.atengk.boot.datasource.security.DataSourcePasswordDecryptor;
import io.github.atengk.boot.sample.datasource.vo.PasswordCipherVerifyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 密码解密安全场景服务
 * <p>
 * 演示 DataSourcePasswordDecryptor 对 SM4 和 AES 密文密码的自动识别与安全解密。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourcePasswordSecurityScenarioService {

    private final DataSourcePasswordDecryptor dataSourcePasswordDecryptor;

    /**
     * 判断密码是否为受管密文格式
     *
     * @param password 待检测密码
     * @return 是否支持解密
     */
    public boolean isEncryptedPassword(String password) {
        return dataSourcePasswordDecryptor.supports(password);
    }

    /**
     * 执行密文解密
     *
     * @param encryptedPassword 密文密码
     * @return 解密后的真实明文
     */
    public String decryptPassword(String encryptedPassword) {
        log.info("[PasswordSecurityScenario] 正在解密密文密码 (前缀脱敏探测)");
        return dataSourcePasswordDecryptor.decrypt(encryptedPassword);
    }

    /**
     * 执行零明文凭据密文格式探测与瞬态解密校验（严禁返回或记录原始明文密码）
     *
     * @param encryptedPassword 待校验加密凭据
     * @return 脱敏后的校验结果视图
     */
    public PasswordCipherVerifyResponse verifyCipherPassword(String encryptedPassword) {
        boolean supported = dataSourcePasswordDecryptor.supports(encryptedPassword);
        String algorithm = resolveAlgorithm(encryptedPassword);
        String masked = DesensitizeUtil.password(encryptedPassword);

        if (!supported) {
            return PasswordCipherVerifyResponse.builder()
                    .supported(false)
                    .verified(false)
                    .algorithm(algorithm)
                    .maskedDigest(masked)
                    .message("非受管密文格式（建议使用 enc:sm4: 或 enc:aes: 密文前缀）")
                    .build();
        }

        try {
            String transientPlaintext = dataSourcePasswordDecryptor.decrypt(encryptedPassword);
            String verifiedMask = transientPlaintext != null && !transientPlaintext.isEmpty()
                    ? DesensitizeUtil.password(transientPlaintext)
                    : "******";
            return PasswordCipherVerifyResponse.builder()
                    .supported(true)
                    .verified(true)
                    .algorithm(algorithm)
                    .maskedDigest(verifiedMask)
                    .message("密文格式受支持且瞬态解密校验成功（零明文透出）")
                    .build();
        } catch (Exception e) {
            log.warn("[PasswordSecurityScenario] 密文瞬态解密校验失败 [algorithm={}, masked={}]", algorithm, masked);
            return PasswordCipherVerifyResponse.builder()
                    .supported(true)
                    .verified(false)
                    .algorithm(algorithm)
                    .maskedDigest(masked)
                    .message("密文格式受支持，但瞬态解密校验失败")
                    .build();
        }
    }

    private String resolveAlgorithm(String encryptedPassword) {
        if (encryptedPassword == null) {
            return "PLAIN";
        }
        if (encryptedPassword.startsWith(CryptoUtilPasswordDecryptor.PREFIX_SM4)) {
            return "SM4";
        }
        if (encryptedPassword.startsWith(CryptoUtilPasswordDecryptor.PREFIX_AES)) {
            return "AES";
        }
        return "PLAIN";
    }
}
