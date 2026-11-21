package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
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
 * 京东云短信服务驱动实现 (JD Cloud SMS)
 * 100% 纯原生实现 {@code JDCLOUD2-HMAC-SHA256} 规范请求、派生密钥链签名与 {@code batchSend} 接口
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class JdCloudSmsProvider extends AbstractHttpSmsProvider {

    /**
     * 默认京东云官方公网接入端点
     */
    public static final String DEFAULT_ENDPOINT = "https://sms.jdcloud-api.com";
    private static final String DEFAULT_REGION = "cn-north-1";
    private static final String SERVICE = "sms";
    private static final String ALGORITHM = "JDCLOUD2-HMAC-SHA256";
    private static final DateTimeFormatter JD_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    public JdCloudSmsProvider(RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
    }

    public JdCloudSmsProvider() {
        this(RestClient.builder());
    }

    @Override
    public SmsSupplier getSupplier() {
        return SmsSupplier.JDCLOUD;
    }

    @Override
    public SmsResult send(SmsRequest request, SmsChannel channel) {
        BusinessAssert.notNull(request, "发信请求 SmsRequest 不能为空");
        BusinessAssert.notNull(channel, "短信渠道 SmsChannel 不能为空");
        BusinessAssert.hasText(channel.getAccessKeyId(), "京东云短信 AccessKeyId 不能为空");
        BusinessAssert.hasText(channel.getAccessKeySecret(), "京东云短信 AccessKeySecret 不能为空");
        BusinessAssert.hasText(request.getPhone(), "接收短信的目标手机号不能为空");
        BusinessAssert.hasText(request.getTemplateId(), "京东云短信模板 ID (templateId) 不能为空");

        String signId = channel.resolveSignIdOrName(request.getTemplateId(), request.getSignName());
        BusinessAssert.hasText(signId, "京东云短信签名 ID (signId / signName) 不能为空");

        String region = StringUtils.hasText(channel.getRegion()) ? channel.getRegion().trim() : DEFAULT_REGION;
        String actionPath = "/v1/regions/" + region + "/batchSend";

        List<String> phoneList = SmsPhoneNormalizer.toDomesticOrE164List(request.getPhones(), channel);
        List<String> orderedParams = request.getParams() != null
                ? request.getParams().toOrderedArray(channel.getParamOrder(request.getTemplateId()))
                : Collections.emptyList();

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("templateId", request.getTemplateId().trim());
        bodyMap.put("signId", signId);
        bodyMap.put("phoneList", phoneList);
        bodyMap.put("params", orderedParams);
        String payloadJson = JsonUtil.toJsonString(bodyMap);

        String endpoint = normalizeEndpoint(channel.getEndpoint(), DEFAULT_ENDPOINT);
        String url = resolveActionUrl(endpoint, actionPath);
        URI uri = URI.create(url);
        String hostHeader = resolveHostHeader(uri);
        String jdDate = JD_DATE_FORMATTER.format(Instant.now());
        String nonce = IdUtil.fastSimpleUUID();

        String authorization = generateAuthorization(
                channel.getAccessKeyId().trim(),
                channel.getAccessKeySecret().trim(),
                region,
                hostHeader,
                uri.getRawPath(),
                payloadJson,
                jdDate,
                nonce
        );

        Map<String, String> headers = new LinkedHashMap<>(6);
        headers.put("Host", hostHeader);
        headers.put("x-jdcloud-algorithm", ALGORITHM);
        headers.put("x-jdcloud-date", jdDate);
        headers.put("x-jdcloud-nonce", nonce);
        headers.put("Authorization", authorization);

        String responseBody = executePostWithRetry(
                channel, "京东云", "batchSend", url, "application/json", headers, payloadJson,
                httpEx -> {
                    Map<String, Object> errRoot = parseJsonSafely(httpEx.getResponseBodyAsString());
                    Object errObj = errRoot.get("error");
                    if (errObj instanceof Map<?, ?> errMap) {
                        String code = errMap.get("code") != null ? String.valueOf(errMap.get("code")) : "HTTP_" + httpEx.getStatusCode().value();
                        String msg = errMap.get("message") != null ? String.valueOf(errMap.get("message")) : httpEx.getStatusText();
                        throw new SmsSendException(channel.getName(), code, msg,
                                SmsResultCode.SMS_SEND_ERROR, "京东云短信发送失败: [" + code + "] " + msg, httpEx);
                    }
                    throwJsonHttpErrorIfPresent(channel, "京东云", httpEx, "code", "message");
                }
        );

        return parseResponse(channel.getName(), request.getPhone(), request.getTemplateId(), responseBody, phoneList.size());
    }

    /**
     * 生成京东云 OpenAPI {@code JDCLOUD2-HMAC-SHA256} Authorization 请求头值
     *
     * @param accessKeyId     Access Key ID
     * @param accessKeySecret Secret Access Key
     * @param region          区域编码（例如 {@code cn-north-1}）
     * @param hostHeader      HTTP Host 头值
     * @param rawPath         请求路径
     * @param payloadJson     请求 JSON 字符串
     * @param jdDate          UTC 时间戳 {@code yyyyMMdd'T'HHmmss'Z'}
     * @param nonce           随机请求流水标识
     * @return Authorization 认证头值
     */
    public static String generateAuthorization(String accessKeyId,
                                               String accessKeySecret,
                                               String region,
                                               String hostHeader,
                                               String rawPath,
                                               String payloadJson,
                                               String jdDate,
                                               String nonce) {
        BusinessAssert.hasText(accessKeyId, "京东云 accessKeyId 不能为空");
        BusinessAssert.hasText(accessKeySecret, "京东云 accessKeySecret 不能为空");
        BusinessAssert.hasText(region, "京东云 region 不能为空");
        BusinessAssert.hasText(hostHeader, "京东云 hostHeader 不能为空");
        BusinessAssert.hasText(rawPath, "京东云 rawPath 不能为空");
        BusinessAssert.hasText(jdDate, "京东云 jdDate 不能为空");
        BusinessAssert.hasText(nonce, "京东云 nonce 不能为空");

        String dateShort = jdDate.substring(0, 8);
        String canonicalUri = rfc3986EncodeExceptSlash(rawPath);
        String canonicalHeaders = "content-type:application/json\n"
                + "host:" + hostHeader.toLowerCase(Locale.ROOT) + "\n"
                + "x-jdcloud-date:" + jdDate + "\n"
                + "x-jdcloud-nonce:" + nonce + "\n";
        String signedHeaders = "content-type;host;x-jdcloud-date;x-jdcloud-nonce";
        String payloadHash = DigestUtil.sha256Hex(payloadJson != null ? payloadJson : "");

        String canonicalRequest = "POST\n" + canonicalUri + "\n\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        String scope = dateShort + "/" + region + "/" + SERVICE + "/jdcloud2_request";
        String stringToSign = ALGORITHM + "\n" + jdDate + "\n" + scope + "\n"
                + DigestUtil.sha256Hex(canonicalRequest);

        byte[] kDate = HmacUtil.hmacSha256(dateShort.getBytes(StandardCharsets.UTF_8),
                ("JDCLOUD2" + accessKeySecret).getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = HmacUtil.hmacSha256(region.getBytes(StandardCharsets.UTF_8), kDate);
        byte[] kService = HmacUtil.hmacSha256(SERVICE.getBytes(StandardCharsets.UTF_8), kRegion);
        byte[] kSigning = HmacUtil.hmacSha256("jdcloud2_request".getBytes(StandardCharsets.UTF_8), kService);
        String signature = HmacUtil.hmacSha256Hex(stringToSign.getBytes(StandardCharsets.UTF_8), kSigning);

        return ALGORITHM + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    @SuppressWarnings("unchecked")
    private SmsResult parseResponse(String channelName, String phone, String templateId, String responseBody, int phoneCount) {
        Map<String, Object> root = parseJsonSafely(responseBody);
        String requestId = root.get("requestId") != null ? String.valueOf(root.get("requestId")) : null;

        Object errObj = root.get("error");
        if (errObj instanceof Map<?, ?> errMap) {
            String code = errMap.get("code") != null ? String.valueOf(errMap.get("code")) : "JD_ERROR";
            String msg = errMap.get("message") != null ? String.valueOf(errMap.get("message")) : "JDCloud gateway error";
            throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                    "京东云短信发送失败: [" + code + "] " + msg);
        }

        Object resultObj = root.get("result");
        if (!(resultObj instanceof Map<?, ?> resultRaw)) {
            throw new SmsSendException(channelName, "INVALID_RESPONSE", "京东云短信响应缺少 result 节点",
                    SmsResultCode.SMS_SEND_ERROR, "京东云短信响应格式异常: " + responseBody);
        }
        Map<String, Object> resultMap = (Map<String, Object>) resultRaw;
        boolean status = Boolean.TRUE.equals(resultMap.get("status"));
        if (!status) {
            String code = resultMap.get("code") != null ? String.valueOf(resultMap.get("code")) : "FAILED";
            String msg = resultMap.get("message") != null ? String.valueOf(resultMap.get("message")) : "Send failed";
            throw new SmsSendException(channelName, code, msg, SmsResultCode.SMS_SEND_ERROR,
                    "京东云短信发送失败: [" + code + "] " + msg);
        }

        String bizId = requestId;
        if (resultMap.get("data") instanceof Map<?, ?> dataMap) {
            Object seq = dataMap.get("sequenceNumber");
            if (seq != null) {
                bizId = String.valueOf(seq);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[JdCloudSmsProvider] 京东云发信成功 -> 渠道: {}, 目标号码: {}, 模板ID: {}, SequenceNumber: {}, RequestId: {}",
                    channelName, DesensitizeUtil.mobilePhone(phone), templateId, bizId, requestId);
        }
        return SmsResult.success(channelName, SmsSupplier.JDCLOUD, bizId, requestId, phoneCount);
    }
}
