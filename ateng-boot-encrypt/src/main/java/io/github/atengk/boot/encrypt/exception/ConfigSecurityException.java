package io.github.atengk.boot.encrypt.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * 配置安全合规守卫阻断异常
 * 用于拦截生产环境敏感字段裸明文或禁止的密钥配置方式
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class ConfigSecurityException extends BusinessException {

    public ConfigSecurityException(String message) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, message);
    }

    public ConfigSecurityException(String message, Throwable cause) {
        super(CommonResultCode.INTERNAL_SERVER_ERROR, message, cause);
    }
}
