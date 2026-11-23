package io.github.atengk.boot.sms.callback;

/**
 * 入站 Webhook 回调安全验签与来源防卫 SPI
 * <p>
 * 支持业务自定义各云厂商 Webhook 签名校验算法（如阿里云/腾讯云/云片自定义签名头）或来源 IP 鉴权逻辑。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@FunctionalInterface
public interface SmsCallbackVerifier {

    /**
     * 执行入站回调安全校验
     *
     * @param context 回调校验上下文
     * @return 校验结果（包含是否放行与 401/403 状态码）
     */
    SmsCallbackVerificationResult verify(SmsCallbackVerificationContext context);
}
