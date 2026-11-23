package io.github.atengk.boot.sms.callback;

import io.github.atengk.boot.core.util.CidrMatcher;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.properties.SmsCallbackProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 默认入站 Webhook 回调安全校验器
 * <p>
 * 内置三道防卫：
 * 1. 基于 Core {@link CidrMatcher} 的来源 IP 网段白名单校验（未命中返回 403）；
 * 2. 基于 {@link MessageDigest#isEqual} 的恒定时间静态令牌校验，默认禁止 URL Query 明文传令牌（未通过返回 401）；
 * 3. 开启 {@code signature-required} 时校验 {@code X-Callback-Signature}（HMAC-SHA256 Hex），防止伪造推送（未通过返回 401）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class DefaultSmsCallbackVerifier implements SmsCallbackVerifier {

    /**
     * 回调令牌请求头名称
     */
    public static final String HEADER_CALLBACK_TOKEN = "X-Callback-Token";

    /**
     * 默认回调 HMAC-SHA256 签名请求头名称
     */
    public static final String HEADER_CALLBACK_SIGNATURE = "X-Callback-Signature";

    private final SmsCallbackProperties properties;
    private final boolean checkDefaultSignature;

    public DefaultSmsCallbackVerifier(SmsCallbackProperties properties) {
        this(properties, true);
    }

    public DefaultSmsCallbackVerifier(SmsCallbackProperties properties, boolean checkDefaultSignature) {
        this.properties = properties != null ? properties : new SmsCallbackProperties();
        this.checkDefaultSignature = checkDefaultSignature;
    }

    @Override
    public SmsCallbackVerificationResult verify(SmsCallbackVerificationContext context) {
        if (context == null) {
            return SmsCallbackVerificationResult.unauthorized("Missing callback verification context");
        }

        // 1. 来源 IP CIDR 白名单防卫 (403 Forbidden)
        List<String> allowedCidrs = properties.getAllowedCidrs();
        if (allowedCidrs != null && !allowedCidrs.isEmpty()) {
            String remoteIp = context.getRemoteIp();
            if (!StringUtils.hasText(remoteIp) || !CidrMatcher.matchesAny(remoteIp, allowedCidrs)) {
                log.warn("[DefaultSmsCallbackVerifier] 渠道 [{}] 回调来源 IP [{}] 不在白名单 {} 内，拒绝访问",
                        context.getChannelName(), remoteIp, allowedCidrs);
                return SmsCallbackVerificationResult.forbidden("Source IP is not in callback allowed-cidrs");
            }
        }

        // 2. 恒定时间令牌防伪校验 (401 Unauthorized)
        String expectedToken = properties.getToken();
        if (StringUtils.hasText(expectedToken)) {
            String providedToken = resolveProvidedToken(context);
            if (!constantTimeEquals(expectedToken, providedToken)) {
                log.warn("[DefaultSmsCallbackVerifier] 渠道 [{}] 回调安全令牌校验失败 (allowQueryToken={})",
                        context.getChannelName(), properties.isAllowQueryToken());
                return SmsCallbackVerificationResult.unauthorized("Invalid callback token");
            }
        } else if (!properties.isAllowQueryToken() && context.isQueryTokenPresent()) {
            log.warn("[DefaultSmsCallbackVerifier] 渠道 [{}] 禁止通过 URL Query 传递回调令牌", context.getChannelName());
            return SmsCallbackVerificationResult.unauthorized("Query token is disabled");
        }

        // 3. 厂商/网关签名校验 (401 Unauthorized)
        if (checkDefaultSignature && properties.isSignatureRequired()) {
            String signature = context.getHeaders() != null
                    ? context.getHeaders().getFirst(HEADER_CALLBACK_SIGNATURE)
                    : null;
            String secret = resolveSigningSecret(context.getChannel());
            if (!StringUtils.hasText(signature) || !StringUtils.hasText(secret)) {
                log.warn("[DefaultSmsCallbackVerifier] 渠道 [{}] 缺少回调签名或签名密钥", context.getChannelName());
                return SmsCallbackVerificationResult.unauthorized("Missing callback signature");
            }
            String payload = context.getRawBody() != null ? context.getRawBody() : "";
            String expectedSignature = HmacUtil.hmacSha256Hex(payload, secret);
            if (!HmacUtil.verifyHex(expectedSignature, signature.trim())) {
                log.warn("[DefaultSmsCallbackVerifier] 渠道 [{}] 回调报文签名校验失败", context.getChannelName());
                return SmsCallbackVerificationResult.unauthorized("Invalid callback signature");
            }
        }

        return SmsCallbackVerificationResult.ok();
    }

    private String resolveProvidedToken(SmsCallbackVerificationContext context) {
        HttpHeaders headers = context.getHeaders();
        String headerToken = headers != null ? headers.getFirst(HEADER_CALLBACK_TOKEN) : null;
        if (StringUtils.hasText(headerToken)) {
            return headerToken;
        }
        if (!properties.isAllowQueryToken()) {
            return null;
        }
        if (StringUtils.hasText(context.getQueryToken())) {
            return context.getQueryToken();
        }
        MultiValueMap<String, String> formParams = context.getFormParams();
        return formParams != null ? formParams.getFirst("token") : null;
    }

    private String resolveSigningSecret(SmsChannel channel) {
        if (StringUtils.hasText(properties.getToken())) {
            return properties.getToken();
        }
        return channel != null ? channel.getAccessKeySecret() : null;
    }

    /**
     * 基于 SHA-256 摘要 + {@link MessageDigest#isEqual} 的恒定时间比对（消除长度与内容时序侧信道）
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedDigest = DigestUtil.sha256(expected.getBytes(StandardCharsets.UTF_8));
        byte[] actualDigest = DigestUtil.sha256(actual.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expectedDigest, actualDigest);
    }
}
