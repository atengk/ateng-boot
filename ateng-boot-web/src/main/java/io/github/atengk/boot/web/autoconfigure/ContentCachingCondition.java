package io.github.atengk.boot.web.autoconfigure;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 通用请求体可重读管道条件装配判定器
 * 当开启 API 加签验签 (ateng.boot.web.sign.enabled=true)、开启 API 传输层混合加解密 (ateng.boot.web.crypto.enabled=true)
 * 或开启 HTTP Payload 采样日志 (ateng.boot.web.payload-log.enabled=true) 时激活
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class ContentCachingCondition extends AnyNestedCondition {

    public ContentCachingCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "ateng.boot.web.sign", name = "enabled", havingValue = "true")
    static class SignEnabledCondition {
    }

    @ConditionalOnProperty(prefix = "ateng.boot.web.crypto", name = "enabled", havingValue = "true")
    static class CryptoEnabledCondition {
    }

    @ConditionalOnProperty(prefix = "ateng.boot.web.payload-log", name = "enabled", havingValue = "true")
    static class PayloadLogEnabledCondition {
    }
}
