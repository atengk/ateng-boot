package io.github.atengk.boot.encrypt.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * 配置解密失败异常
 * 精确指示出错属性 Key 名称，严格禁止在日志与堆栈中打印解密前密文与明文
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class ConfigDecryptionException extends BusinessException {

    private final String propertyKey;
    private final String keyId;
    private final String algorithm;

    public ConfigDecryptionException(String propertyKey, String keyId, String algorithm, Throwable cause) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR,
                String.format("配置项解密失败 [Key: '%s', KeyId: '%s', Algorithm: '%s']，原因: %s",
                        propertyKey,
                        keyId != null ? keyId : "default",
                        algorithm != null ? algorithm : "unknown",
                        cause != null ? cause.getMessage() : "未知错误"),
                cause);
        this.propertyKey = propertyKey;
        this.keyId = keyId;
        this.algorithm = algorithm;
    }

    public ConfigDecryptionException(String propertyKey, String message, Throwable cause) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR,
                String.format("配置项解密失败 [Key: '%s']，原因: %s", propertyKey, message), cause);
        this.propertyKey = propertyKey;
        this.keyId = null;
        this.algorithm = null;
    }
}
