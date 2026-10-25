package io.github.atengk.boot.datasource.security;

/**
 * 数据源密码安全解密 SPI
 * 遵循 Zero-Bloat 原则与零明文安全不变量，在内存中瞬态解密数据源密文凭据，
 * 彻底杜绝明文凭据持久化至配置对象或在日志与健康检查端点中泄露
 *
 * @author Ateng
 * @since 2026-10-24
 */
public interface DataSourcePasswordDecryptor {

    /**
     * 判断当前解密器是否支持该密码密文格式
     *
     * @param encryptedPassword 待检测的密码字符串
     * @return true 若支持解密，false 否则
     */
    boolean supports(String encryptedPassword);

    /**
     * 将密文密码解密为纯内存明文字符串
     *
     * @param encryptedPassword 密文密码字符串
     * @return 解密后的明文密码；若入参不支持或无需解密则保底返回原始值
     */
    String decrypt(String encryptedPassword);
}
