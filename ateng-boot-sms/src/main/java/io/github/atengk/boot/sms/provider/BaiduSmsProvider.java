package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsParam;
import io.github.atengk.boot.sms.model.SmsRequest;
import io.github.atengk.boot.sms.model.SmsResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.support.SmsPhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 百度智能云短信服务驱动实现 (BCE SMS v3)
 * 基于 Spring 6 RestClient 与 JDK 17 + Core 原生 HMAC 原语实现 {@code bce-auth-v1} 签名鉴权
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class BaiduSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认百度智能云官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://smsv3.bj.baidubce.com";

    private static final String SEND_PATH = "/api/v3/sendSms";
    private static final int AUTH_EXPIRATION_SECONDS = 1800;
    private static final DateTimeFormatter BCE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    public BaiduSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public BaiduSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.BAIDU;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "百度智能云短信 AccessKeyId 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "百度智能云短信 AccessKeySecret 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "百度智能云短信模板 ID (template) 不能为空");

        String signatureId = channel.resolveSignIdOrName(request.getTemplateId(), request.getSignName());
        BusinessAssert.hasText(signatureId, "百度智能云短信签名 ID (signId / signName) 不能为空");

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("mobile", String.join(",", formattedPhones));
        bodyMap.put("template", request.getTemplateId().trim());
        bodyMap.put("signatureId", signatureId);
        bodyMap.put("contentVar", SmsParam.toMap(
                request.getParams(), channel.getParamOrder(request.getTemplateId())));

        String custom = channel.getExtra("custom");
        if (StringUtils.hasText(custom)) {
            bodyMap.put("custom", custom);
        }
        if (StringUtils.hasText(channel.getExtCode())) {
            bodyMap.put("userExtId", channel.getExtCode().trim());
        }
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String url = resolveActionUrl(endpoint, SEND_PATH);
        URI uri = URI.create(url);
        String hostHeader = resolveHostHeader(uri);
        String bceDate = BCE_DATE_FORMATTER.format(Instant.now());

        String authorization = generateAuthorization(
                channel.getAccessKeyId().trim(),
                channel.getAccessKeySecret().trim(),
                hostHeader,
                uri.getRawPath(),
                bceDate
        );

        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put("Host", hostHeader);
        headers.put("x-bce-date", bceDate);
        headers.put("Authorization", authorization);

        String responseBody = executePostWithRetry(
                channel, "百度智能云", "sendSms", url, "application/json", headers, payloadJson,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "百度智能云", httpEx, "code", "message")
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, formattedPhones.size());
    }

    /**
     * 生成百度智能云 BCE Auth v1 Authorization 请求头值
     *
     * @param accessKeyId     Access Key ID
     * @param accessKeySecret Secret Access Key
     * @param hostHeader      目标 HTTP Host 头（含端口）
     * @param rawPath         请求路径（例如 {@code /api/v3/sendSms}）
     * @param bceDate         UTC ISO-8601 时间戳（{@code yyyy-MM-dd'T'HH:mm:ss'Z'}）
     * @return {@code bce-auth-v1/...} 认证头值
     */
    public static String generateAuthorization(String accessKeyId,
                                               String accessKeySecret,
                                               String hostHeader,
                                               String rawPath,
                                               String bceDate) {
        BusinessAssert.hasText(accessKeyId, "百度智能云 accessKeyId 不能为空");
        BusinessAssert.hasText(accessKeySecret, "百度智能云 accessKeySecret 不能为空");
        BusinessAssert.hasText(hostHeader, "百度智能云 hostHeader 不能为空");
        BusinessAssert.hasText(rawPath, "百度智能云 rawPath 不能为空");
        BusinessAssert.hasText(bceDate, "百度智能云 bceDate 不能为空");

        String authPrefix = "bce-auth-v1/" + accessKeyId + "/" + bceDate + "/" + AUTH_EXPIRATION_SECONDS;
        String signingKey = HmacUtil.hmacSha256Hex(authPrefix, accessKeySecret);
        String canonicalUri = rfc3986EncodeExceptSlash(rawPath);
        String canonicalHeaders = "host:" + rfc3986Encode(hostHeader) + "\nx-bce-date:" + rfc3986Encode(bceDate);
        String canonicalRequest = "POST\n" + canonicalUri + "\n\n" + canonicalHeaders;
        String signature = HmacUtil.hmacSha256Hex(canonicalRequest, signingKey);
        return authPrefix + "/host;x-bce-date/" + signature;
    }

    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String code = root.get("code") != null ? String.valueOf(root.get("code")) : "UNKNOWN";
        String message = root.get("message") != null ? String.valueOf(root.get("message")) : "Unknown error";

        if (!"1000".equals(code)) {
            throw new SmsSendException(channelName, code, message, SmsResultCode.SMS_SEND_ERROR,
                    "百度智能云短信发送失败: [" + code + "] " + message);
        }

        String requestId = root.get("requestId") != null ? String.valueOf(root.get("requestId")) : null;
        String bizId = requestId;
        Object dataObj = root.get("data");
        if (dataObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object msgId = ((Map<String, Object>) first).get("messageId");
            if (msgId != null) {
                bizId = String.valueOf(msgId);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[BaiduSmsProvider] 百度智能云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, BizId: {}, RequestId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId, requestId);
        }
        return SmsResult.success(channelName, SmsSupplier.BAIDU, bizId, requestId, phoneCount);
    }
}
