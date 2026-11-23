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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 七牛云短信服务驱动实现 (Qiniu SMS)
 * 100% 纯原生实现 {@code Qiniu AccessKey:HMAC-SHA1-URLSafeBase64} 鉴权与 {@code X-Qiniu-Date} 请求头，支持单条与批量接口自适应路由
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class QiniuSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认七牛云短信官方接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://sms.qiniuapi.com";
    private static final String SINGLE_PATH = "/v1/message/single";
    private static final String BATCH_PATH = "/v1/message";
    private static final DateTimeFormatter QINIU_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    public QiniuSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public QiniuSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.QINIU;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "七牛云短信 AccessKey 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "七牛云短信 SecretKey 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "七牛云短信模板 ID (template_id) 不能为空");

        List<String> formattedPhones = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);
        boolean isBatch = formattedPhones.size() > 1;
        String actionPath = isBatch ? BATCH_PATH : SINGLE_PATH;

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("template_id", request.getTemplateId().trim());
        if (isBatch) {
            bodyMap.put("mobiles", formattedPhones);
        } else {
            bodyMap.put("mobile", formattedPhones.get(0));
        }
        bodyMap.put("parameters", SmsParam.toMap(
                request.getParams(), channel.getParamOrder(request.getTemplateId())));
        if (StringUtils.hasText(channel.getSignId())) {
            bodyMap.put("signature_id", channel.getSignId().trim());
        }
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String url = resolveActionUrl(endpoint, actionPath);
        URI uri = URI.create(url);
        String hostHeader = resolveHostHeader(uri);
        String qiniuDate = QINIU_DATE_FORMATTER.format(Instant.now());

        String authorization = generateAuthorization(
                channel.getAccessKeyId().trim(),
                channel.getAccessKeySecret().trim(),
                hostHeader,
                uri.getRawPath(),
                qiniuDate,
                payloadJson
        );

        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put("Host", hostHeader);
        headers.put("X-Qiniu-Date", qiniuDate);
        headers.put("Authorization", authorization);

        String responseBody = executePostWithRetry(
                channel, "七牛云", isBatch ? "sendBatchSms" : "sendSingleSms", url, "application/json", headers, payloadJson,
                httpEx -> throwJsonHttpErrorIfPresent(channel, "七牛云", httpEx, null, "error")
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, formattedPhones.size());
    }

    /**
     * 生成七牛云管理鉴权 {@code Qiniu AccessKey:EncodedSign} 请求头值
     *
     * @param accessKey   七牛 AccessKey
     * @param secretKey   七牛 SecretKey
     * @param hostHeader  HTTP Host 头
     * @param rawPath     请求路径
     * @param qiniuDate   UTC 时间戳 {@code yyyyMMdd'T'HHmmss'Z'}
     * @param payloadJson 请求体 JSON 字符串
     * @return {@code Qiniu ...} 认证头值
     */
    public static String generateAuthorization(String accessKey,
                                               String secretKey,
                                               String hostHeader,
                                               String rawPath,
                                               String qiniuDate,
                                               String payloadJson) {
        BusinessAssert.hasText(accessKey, "七牛云 accessKey 不能为空");
        BusinessAssert.hasText(secretKey, "七牛云 secretKey 不能为空");
        BusinessAssert.hasText(hostHeader, "七牛云 hostHeader 不能为空");
        BusinessAssert.hasText(rawPath, "七牛云 rawPath 不能为空");
        BusinessAssert.hasText(qiniuDate, "七牛云 qiniuDate 不能为空");

        StringBuilder dataToSign = new StringBuilder()
                .append("POST ").append(rawPath)
                .append("\nHost: ").append(hostHeader)
                .append("\nContent-Type: application/json")
                .append("\nX-Qiniu-Date: ").append(qiniuDate)
                .append("\n\n");
        if (StringUtils.hasText(payloadJson)) {
            dataToSign.append(payloadJson);
        }
        byte[] digest = HmacUtil.hmacSha1(
                dataToSign.toString().getBytes(StandardCharsets.UTF_8),
                secretKey.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getUrlEncoder().encodeToString(digest);
        return "Qiniu " + accessKey + ":" + encoded;
    }

    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        Object errorObj = root.get("error");
        if (errorObj != null && StringUtils.hasText(String.valueOf(errorObj))) {
            String errMsg = String.valueOf(errorObj);
            throw new SmsSendException(channelName, "QINIU_ERROR", errMsg, SmsResultCode.SMS_SEND_ERROR,
                    "七牛云短信发送失败: " + errMsg);
        }

        String bizId = null;
        if (root.get("message_id") != null) {
            bizId = String.valueOf(root.get("message_id"));
        } else if (root.get("job_id") != null) {
            bizId = String.valueOf(root.get("job_id"));
        }

        if (log.isInfoEnabled()) {
            log.info("[QiniuSmsProvider] 七牛云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, BizId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId);
        }
        return SmsResult.success(channelName, SmsSupplier.QINIU, bizId, bizId, phoneCount);
    }
}
