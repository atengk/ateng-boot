package io.github.atengk.boot.sample.datasource.service;

import io.github.atengk.boot.datasource.security.DataSourcePasswordDecryptor;
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
}
